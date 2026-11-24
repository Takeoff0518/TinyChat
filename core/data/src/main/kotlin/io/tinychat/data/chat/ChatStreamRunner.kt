package io.tinychat.data.chat

import io.tinychat.data.db.FragmentDao
import io.tinychat.data.db.MessageDao
import io.tinychat.data.db.toEntity
import io.tinychat.model.Fragment
import io.tinychat.model.FragmentPayloadCodec
import io.tinychat.model.FragmentType
import io.tinychat.model.MessageStatus
import io.tinychat.model.Usage
import io.tinychat.net.MessageAssembler
import io.tinychat.net.StopReason
import io.tinychat.net.StreamAssembler
import io.tinychat.net.transport.ChatException
import io.tinychat.net.transport.ChatTransport
import io.tinychat.net.protocol.PreparedRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** 一次流式生成的结果。 */
data class RunOutcome(
    val messageId: Long,
    val status: MessageStatus,
    val usage: Usage?,
    val stopReason: StopReason?,
    val title: String?,
    val error: ChatException? = null,
)

/**
 * 流式落库与状态流转（M1-5）。
 *
 * 三条硬规则：
 * 1. **先落行再追加**：块一开始就插入空碎片行，后续原地 UPDATE，进程被杀也不会丢半句话；
 * 2. **节流写库**：默认 200ms 一次，逐 delta 写会把 SQLite 打满；
 * 3. **状态只由结局决定**：FINISHED / INCOMPLETE / CONTENT_FILTER / FAILED，
 *    永远不留 WIP（取消也要落 INCOMPLETE）。
 */
class ChatStreamRunner(
    private val messageDao: MessageDao,
    private val fragmentDao: FragmentDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val flushIntervalMillis: Long = 200,
    /** 重试退避；测试传空实现，免得单测真等 1s */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    suspend fun run(
        assistantMessageId: Long,
        request: PreparedRequest,
        transport: ChatTransport,
        /** 协议决定的解码器（M8-1）：Messages 与 OpenAI 各一套，上层不感知差别 */
        assembler: StreamAssembler = MessageAssembler(clock),
    ): RunOutcome {
        // 继续生成（M1-9）会复用一条已有消息：新碎片的 ord 必须接在已有碎片后面，
        // 否则撞上 fragments(message_id, ord) 的唯一索引。
        var ordBase: Int? = null
        val rowIds = HashMap<Int, Long>()
        val written = HashMap<Int, String>()
        var lastFlush = 0L

        suspend fun flush(recovering: Boolean = false) {
            val base = ordBase ?: return
            val fragments = assembler.fragments()
            for (fragment in fragments) {
                val json = FragmentPayloadCodec.encode(fragment.payload)
                if (written[fragment.ord] == json) continue
                // A cancelled insert can commit after Room declines to deliver the returned id.
                val rowId = rowIds[fragment.ord] ?: if (recovering) {
                    fragmentDao.idAt(assistantMessageId, base + fragment.ord)
                } else {
                    null
                }
                if (rowId == null) {
                    rowIds[fragment.ord] = fragmentDao.insert(fragment.forMessage(assistantMessageId, base).toEntity())
                } else {
                    fragmentDao.updateTypeAndPayload(rowId, fragment.payload.typeWire, json)
                }
                written[fragment.ord] = json
            }
            lastFlush = clock()
        }

        suspend fun flushIfDue() {
            if (clock() - lastFlush >= flushIntervalMillis) flush()
        }

        try {
            ordBase = fragmentDao.nextOrd(assistantMessageId)
            streamWithRetry(request, transport, assembler) { flushIfDue() }
            assembler.finishUnpairedTools()
            return withContext(NonCancellable) {
                flush()
                finalize(assistantMessageId, assembler)
            }
        } catch (e: ChatException) {
            withContext(NonCancellable) {
                runCatching { flush(recovering = true) }
                messageDao.setStatus(assistantMessageId, MessageStatus.FAILED)
                // 失败原因必须落库：界面上「断网重试圆钮」靠 error_kind 判断
                runCatching { messageDao.setError(assistantMessageId, e.kind, e.message) }
            }
            return RunOutcome(
                messageId = assistantMessageId,
                status = MessageStatus.FAILED,
                usage = assembler.usage,
                stopReason = assembler.stopReason,
                title = assembler.title,
                error = e,
            )
        } catch (e: CancellationException) {
            // 用户点了停止：已生成的内容必须保留
            withContext(NonCancellable) {
                runCatching { flush(recovering = true) }
                messageDao.markInterrupted(assistantMessageId)
            }
            throw e
        }
    }

    /**
     * 自动重试（M1-11）：仅限「还没收到任何帧」时。
     *
     * **铁律**：一旦收到过 `content_block_start`，再重试就会重复计费并打乱内容，
     * 因此 [sawFrame] 一旦为真，后面任何错误都直接抛给上层。
     *
     * 退避：429 优先用服务端 `Retry-After`，否则 1s；网络/5xx 依次 1s、2s。
     */
    private suspend fun streamWithRetry(
        request: PreparedRequest,
        transport: ChatTransport,
        assembler: StreamAssembler,
        flush: suspend () -> Unit,
    ) {
        var attempt = 0
        while (true) {
            var sawFrame = false
            try {
                transport.stream(request).collect { event ->
                    sawFrame = true
                    assembler.onFrame(event.name, event.data)
                    flush()
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChatException) {
                val canRetry = !sawFrame && e.kind.retryable && attempt < e.kind.maxAutoRetries
                if (!canRetry) throw e
                attempt++
                sleep(e.retryAfterMillis ?: backoffMillis(e.kind, attempt))
            }
        }
    }

    private fun backoffMillis(kind: io.tinychat.model.ErrorKind, attempt: Int): Long =
        when (kind) {
            io.tinychat.model.ErrorKind.HTTP_429 -> 1_000L
            // 网络抖动与服务端故障：1s、2s
            else -> 1_000L shl (attempt - 1)
        }

    private suspend fun finalize(messageId: Long, assembler: StreamAssembler): RunOutcome {
        val stopReason = assembler.stopReason
        val status = when {
            assembler.error != null -> MessageStatus.FAILED
            // 拒答：按协议丢弃已下发的思考与正文，只保留工具块
            stopReason == StopReason.REFUSAL -> MessageStatus.CONTENT_FILTER
            stopReason == StopReason.MAX_TOKENS -> MessageStatus.INCOMPLETE
            assembler.sawMessageStop && stopReason != null -> MessageStatus.FINISHED
            else -> MessageStatus.INCOMPLETE
        }

        if (status == MessageStatus.CONTENT_FILTER) {
            fragmentDao.deleteByTypes(
                messageId,
                listOf(FragmentType.THINK.wire, FragmentType.RESPONSE.wire),
            )
        }

        assembler.thinkingSignature?.let { signature ->
            runCatching { messageDao.setThinkingSignature(messageId, signature) }
        }

        val usage = assembler.usage
        val entity = messageDao.byId(messageId)
        if (entity != null) {
            messageDao.update(
                entity.copy(
                    status = status,
                    promptTokens = usage?.promptTokens,
                    cacheHitTokens = usage?.cacheHitTokens,
                    completionTokens = usage?.completionTokens,
                    thinkingTokens = usage?.thinkingTokens,
                    safetyTokens = usage?.safetyTokens,
                    searchCalls = usage?.searchCalls,
                    fetchCalls = usage?.fetchCalls,
                    imageSearchCalls = usage?.imageSearchCalls,
                    accumulatedTokenUsage = usage?.contextUsed ?: entity.accumulatedTokenUsage,
                    errorKind = if (status == MessageStatus.FAILED) {
                        assembler.error?.let { io.tinychat.model.ErrorKind.STREAM }
                    } else {
                        null
                    },
                    errorMessage = assembler.error?.message,
                ),
            )
        }

        return RunOutcome(
            messageId = messageId,
            status = status,
            usage = usage,
            stopReason = stopReason,
            title = assembler.title,
        )
    }

    private fun Fragment.forMessage(messageId: Long, ordBase: Int): Fragment =
        copy(messageId = messageId, ord = ordBase + ord)
}
