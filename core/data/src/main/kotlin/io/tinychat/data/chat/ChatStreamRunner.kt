package io.tinychat.data.chat

import io.tinychat.data.db.FragmentDao
import io.tinychat.data.db.MessageDao
import io.tinychat.data.db.toEntity
import io.tinychat.model.Fragment
import io.tinychat.model.FragmentPayloadCodec
import io.tinychat.model.MessageStatus
import io.tinychat.model.Usage
import io.tinychat.net.LiveDelta
import io.tinychat.net.MessageAssembler
import io.tinychat.net.SseEvent
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
 * 生成过程中的实时快照：界面直接读它出字，**不经过数据库**。
 *
 * [text] 是这一轮到目前为止的**完整正文**（继续生成时把这条消息原有的内容接在前面），
 * [thinking] 是当前这段思考的全文。给快照而不是增量：界面拿一次就能直接渲染，
 * 不必自己维护拼接状态，也不怕和落库的形态对不上。
 *
 * [thinkingDone] 是「这一段思考写完了没有」。界面靠它决定标签写 `正在思考` 还是
 * `已思考（用时 N 秒）`、图标是不是还在呼吸、以及默认要不要收起。**默认 true**：
 * 没开思考的会话不该凭空冒出一行「正在思考」。`ThinkStart` 置回 false，
 * `ThinkStop` 置 true。
 *
 * [thinkingSeconds] 是到目前为止的思考耗时（多轮「思考、搜索、再思考」时累加），
 * 有它才能在思考块停下的那一帧就把用时写出来，不用等 500ms 的落库。
 */
data class LiveStream(
    val messageId: Long,
    val thinking: String,
    val text: String,
    val thinkingDone: Boolean = true,
    val thinkingSeconds: Double? = null,
)

/** 实时快照的接收端。传 `null` 表示这一轮不要实时预览（单测这类场景）。 */
fun interface LiveSink {
    fun onSnapshot(snapshot: LiveStream)
}

/**
 * 流式落库与状态流转（M1-5）。
 *
 * 四条硬规则：
 * 1. **先落行再追加**：块一开始就插入空碎片行，后续原地 UPDATE，进程被杀也不会丢半句话；
 * 2. **节流写库**：默认 500ms 一次，逐 delta 写会把 SQLite 打满；
 * 3. **界面不读库**：出字靠 [LiveSink] 的实时快照，落库只负责防丢与历史（决策 74）；
 * 4. **状态只由结局决定**：FINISHED / INCOMPLETE / CONTENT_FILTER / FAILED，
 *    永远不留 WIP（取消也要落 INCOMPLETE）。
 */
class ChatStreamRunner(
    private val messageDao: MessageDao,
    private val fragmentDao: FragmentDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val flushIntervalMillis: Long = 500,
    /**
     * 实时快照的最小间隔：跟屏幕刷新走（决策 74）。
     *
     * 曾经在思考阶段单独放到 45ms，理由是「每来一帧都要把整段思维链重排一次」。那个理由
     * 建立在 **debug 包** 的实测上（平铺 41% / 收起 25%，用的还是 120Hz 下按 8.3ms 算的
     * `Janky frames (legacy)`）；换成 release 包、改看 `Frame deadline missed` 之后，两种
     * 模式都在 4% 上下、95 分位 12–13ms，看不出要降频的理由，于是撤回。
     * 以后量手感一律用 release（或 benchmark）包，别看 legacy 那一行。
     */
    private val liveIntervalMillis: Long = 16,
    /** 重试退避；测试传空实现，免得单测真等 1s */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    suspend fun run(
        assistantMessageId: Long,
        request: PreparedRequest,
        transport: ChatTransport,
        /** 协议决定的解码器（M8-1）：Messages 与 OpenAI 各一套，上层不感知差别 */
        assembler: StreamAssembler = MessageAssembler(clock),
        /** 继续生成（M1-9）：这条消息原有正文，实时快照要接在前面，否则界面会先闪掉前半段 */
        baseText: String = "",
        /** 界面实时预览；`null` = 不要（单测等场景） */
        onLive: LiveSink? = null,
    ): RunOutcome {
        // 继续生成（M1-9）会复用一条已有消息：新碎片的 ord 必须接在已有碎片后面，
        // 否则撞上 fragments(message_id, ord) 的唯一索引。
        var ordBase: Int? = null
        val rowIds = HashMap<Int, Long>()
        val written = HashMap<Int, String>()
        var lastFlush = 0L

        // —— 实时出字（决策 74）：只有内存，不碰库 ——
        val liveText = StringBuilder(baseText)
        val liveThink = StringBuilder()
        var lastLiveAt = Long.MIN_VALUE / 2
        // 这一段思考写完没有、到目前为止想了多久（决策 80）
        var liveThinkingDone = true
        var liveThinkingSeconds = 0.0

        fun publishLive(force: Boolean = false) {
            val sink = onLive ?: return
            val now = clock()
            if (!force && now - lastLiveAt < liveIntervalMillis) return
            lastLiveAt = now
            sink.onSnapshot(
                LiveStream(
                    messageId = assistantMessageId,
                    thinking = liveThink.toString(),
                    text = liveText.toString(),
                    thinkingDone = liveThinkingDone,
                    thinkingSeconds = liveThinkingSeconds.takeIf { it > 0 },
                ),
            )
        }

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
            streamWithRetry(request, transport) { event ->
                assembler.onFrame(event.name, event.data)
                assembler.drainLive { delta ->
                    when (delta) {
                        LiveDelta.ThinkStart -> {
                            liveThink.setLength(0)
                            liveThinkingDone = false
                        }

                        is LiveDelta.Think -> liveThink.append(delta.text)
                        is LiveDelta.ThinkStop -> {
                            liveThinkingDone = true
                            delta.elapsedSecs?.let { liveThinkingSeconds += it }
                        }

                        is LiveDelta.Text -> liveText.append(delta.text)
                    }
                }
                if (liveText.isNotEmpty() || liveThink.isNotEmpty()) publishLive()
                flushIfDue()
            }
            assembler.finishUnpairedTools()
            // 收尾这一帧必须发出去：界面要靠它拿到「完整的正文」再交给数据库
            publishLive(force = true)
            return withContext(NonCancellable) {
                flush()
                finalize(assistantMessageId, assembler)
            }
        } catch (e: ChatException) {
            publishLive(force = true)
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
            // 用户点了停止：已生成的内容必须保留。
            // 协程此时已是取消状态，普通挂起调用会立刻抛 CancellationException，
            // 所以补落库与状态回写都放进不可取消的上下文——否则最后一段会丢。
            publishLive(force = true)
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
        onEvent: suspend (SseEvent) -> Unit,
    ) {
        var attempt = 0
        while (true) {
            var sawFrame = false
            try {
                transport.stream(request).collect { event ->
                    sawFrame = true
                    onEvent(event)
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
            // 拒答（决策 85）：已下发的思考与正文**保留**——模型通常已经说了一句拒绝的话，
            // 以前按协议一刀切删掉，界面上就只剩一个没有回答的提问。回传上下文时整轮剔除，
            // 那条规则在 ContextRebuilder 里，不靠删库实现。
            stopReason == StopReason.REFUSAL -> MessageStatus.CONTENT_FILTER
            stopReason == StopReason.MAX_TOKENS -> MessageStatus.INCOMPLETE
            assembler.sawMessageStop && stopReason != null -> MessageStatus.FINISHED
            else -> MessageStatus.INCOMPLETE
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
