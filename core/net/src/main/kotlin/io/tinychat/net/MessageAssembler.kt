package io.tinychat.net

import io.tinychat.model.Fragment
import io.tinychat.model.FragmentPayload
import io.tinychat.model.ReadLinkPayload
import io.tinychat.model.ResponsePayload
import io.tinychat.model.SearchResult
import io.tinychat.model.ThinkPayload
import io.tinychat.model.ToolFindPayload
import io.tinychat.model.ToolOpenPayload
import io.tinychat.model.ToolSearchPayload
import io.tinychat.model.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 终止原因。只保留协议里真实出现过的值，其余归 UNKNOWN。 */
enum class StopReason(val wire: String) {
    END_TURN("end_turn"),
    MAX_TOKENS("max_tokens"),
    STOP_SEQUENCE("stop_sequence"),
    REFUSAL("refusal"),
    TOOL_USE("tool_use"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(value: String?): StopReason? =
            if (value == null) null else entries.firstOrNull { it.wire == value } ?: UNKNOWN
    }
}

/** 流中的错误帧（服务端主动下发）。 */
data class StreamError(val type: String?, val message: String?)

/**
 * 事件 → 碎片归并器（M1-4）。
 *
 * 输入是 [SseReader] 产出的帧，输出是与导出 v3 同构的碎片序列。要点：
 * 1. `server_tool_use` 与结果块按 `tool_use_id` **配对**，结果可能乱序到达；
 * 2. 碎片 `ord` 在**调用块到达时**就分配，保证导出顺序 = 流顺序；
 * 3. 未配对的调用在流结束时按 `WIP` 落成碎片（用户中途停止的情形）；
 * 4. 未知事件只登记不抛异常（官方随时可能加帧）。
 *
 * 刻意不做的事：不判断「这条回复能否进上下文」（那是 M1-10）、不落库（M1-5）。
 */
class MessageAssembler(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : StreamAssembler {

    private class Slot(
        val ord: Int,
        val startedAt: Long,
        var payload: FragmentPayload?,
    )

    var messageId: String? = null
        private set
    var model: String? = null
        private set
    override var stopReason: StopReason? = null
        private set
    var stopDetailsType: String? = null
        private set
    override var title: String? = null
        private set
    override var usage: Usage? = null
        private set
    override var error: StreamError? = null
        private set
    override var sawMessageStop: Boolean = false
        private set

    /** 未识别的事件名/块类型，仅用于诊断 */
    val unknownEvents: MutableList<String> = mutableListOf()

    private val slots = ArrayList<Slot?>()
    private val slotByBlockIndex = HashMap<Int, Int>()
    private val slotByToolId = HashMap<String, Int>()
    private val pendingToolAction = HashMap<Int, String>()
    private val pendingToolInput = HashMap<Int, JsonObject>()

    /** 调用块整块（`server_tool_use`）：read_link 回放时必须与结果成对发回 */
    private val pendingToolCall = HashMap<Int, JsonObject>()
    /** 结果块先于调用块到达时的暂存：tool_use_id → (root, block) */
    private val orphanResults = HashMap<String, Pair<JsonObject, JsonObject>>()
    private var thinkingBlocks = 0

    /** 思考块的签名（`signature_delta`），按 ord 记；续写那一轮要原样回传 */
    private val signatures = mutableMapOf<Int, StringBuilder>()

    /** 本轮最后一个思考签名（落到 messages.thinking_signature，仅本地） */
    override var thinkingSignature: String? = null
        private set
    private var toolCalls = 0
    private var fetchCalls = 0

    fun onEvents(events: Sequence<SseEvent>) {
        events.forEach { onFrame(it.name, it.data) }
    }

    override fun onFrame(name: String?, data: String) {
        val root = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: run {
            unknownEvents += "非 JSON 帧(event=$name)"
            return
        }
        // 规范：以 JSON type 为准，event 名只做兜底
        val type = root.string("type") ?: name ?: run {
            unknownEvents += "无 type 帧"
            return
        }

        when (type) {
            "message_start" -> onMessageStart(root)
            "content_block_start" -> onBlockStart(root)
            "content_block_delta" -> onBlockDelta(root)
            "content_block_stop" -> onBlockStop(root)
            "message_delta" -> onMessageDelta(root)
            "message_stop" -> sawMessageStop = true
            "ping" -> Unit
            "error" -> onError(root)
            else -> unknownEvents += "event:$type"
        }
    }

    /** 本帧产生的实时增量，交给上层逐帧出字；读完即清 */
    private val liveQueue = ArrayList<LiveDelta>(2)

    override fun drainLive(into: (LiveDelta) -> Unit) {
        for (delta in liveQueue) into(delta)
        liveQueue.clear()
    }

    /** 归并结果：按 ord 升序，跳过空洞 */
    override fun fragments(): List<Fragment> {
        val out = ArrayList<Fragment>(slots.size)
        for (slot in slots) {
            val payload = slot?.payload ?: continue
            out += Fragment(messageId = 0, ord = slot.ord, payload = payload)
        }
        return out
    }

    /** 本轮是否用过服务端联网工具（继续生成的第一条前置条件） */
    override fun usedServerTools(): Boolean = toolCalls > 0

    fun toolCallCount(): Int = toolCalls

    fun fetchCallCount(): Int = fetchCalls

    // —— 事件处理 ——

    private fun onMessageStart(root: JsonObject) {
        val message = root["message"] as? JsonObject ?: return
        messageId = message.string("id")
        model = message.string("model")
    }

    private fun onBlockStart(root: JsonObject) {
        val index = root.int("index") ?: slots.size
        val block = root["content_block"] as? JsonObject ?: return
        val blockType = block.string("type") ?: return

        when (blockType) {
            "thinking" -> {
                thinkingBlocks++
                val slot = newSlot(ThinkPayload(content = ""))
                slotByBlockIndex[index] = slot.ord
                // 上一段思考已经结束（真出现时是「思考—搜索—再思考」），界面要清空重来
                liveQueue += LiveDelta.ThinkStart
            }

            "text" -> {
                val slot = newSlot(ResponsePayload(content = ""))
                slotByBlockIndex[index] = slot.ord
            }

            "server_tool_use" -> {
                toolCalls++
                val action = (block["input"] as? JsonObject).string("action") ?: "unknown"
                if (action == "open_page" || action == "find_in_page") fetchCalls++
                val toolId = block.string("id") ?: block.string("tool_use_id")
                // ord 现在就占位，payload 等结果块到了再填
                val slot = newSlot(null)
                pendingToolAction[slot.ord] = action
                pendingToolCall[slot.ord] = block
                pendingToolInput[slot.ord] = (block["input"] as? JsonObject) ?: JsonObject(emptyMap())
                if (toolId != null) {
                    slotByToolId[toolId] = slot.ord
                    toolToId[slot.ord] = toolId
                    // 结果块先到的极端情况：调用块到达时立即补配对
                    orphanResults.remove(toolId)?.let { (orphanRoot, orphanBlock) ->
                        dispatchResult(orphanRoot, orphanBlock)
                    }
                }
            }

            "web_search_tool_result" -> onSearchResult(root, block)

            "web_fetch_tool_result" -> onFetchResult(root, block)

            else -> {
                unknownEvents += "block:$blockType"
                if (blockType == "tool_result" || blockType == "server_tool_result") {
                    // 防御：真出现时按结果块处理
                    onFetchResult(root, block)
                }
            }
        }
    }

    private fun onSearchResult(root: JsonObject, block: JsonObject) {
        val toolId = block.string("tool_use_id")
        if (toolId != null && !slotByToolId.containsKey(toolId)) {
            orphanResults[toolId] = root to block
            return
        }
        val ord = toolId?.let { slotByToolId[it] } ?: nextUnpairedOrd()
        val content = block["content"]

        val results = ArrayList<SearchResult>()
        var failed = false
        when (content) {
            is JsonArray -> content.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                if (item.string("type")?.contains("error") == true) {
                    failed = true
                    return@forEach
                }
                results += SearchResult(
                    url = item.string("url") ?: "",
                    title = item.string("title") ?: "",
                    snippet = item.string("snippet"),
                    publishedAt = item.string("page_age") ?: item.string("published_at"),
                    siteIcon = item.string("site_icon"),
                    siteName = item.string("site_name"),
                )
            }

            is JsonObject -> failed = content.string("type")?.contains("error") == true
            else -> Unit
        }

        val queries = pendingToolInput[ord]?.let { input ->
            (input["queries"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        } ?: emptyList()

        fill(
            ord,
            ToolSearchPayload(
                status = if (failed) STATUS_FAILED else STATUS_FINISHED,
                stageId = stageOf(ord),
                queries = queries,
                results = results,
            ),
        )
    }

    private fun onFetchResult(root: JsonObject, block: JsonObject) {
        val toolId = block.string("tool_use_id")
        if (toolId != null && !slotByToolId.containsKey(toolId)) {
            orphanResults[toolId] = root to block
            return
        }
        val ord = toolId?.let { slotByToolId[it] } ?: nextUnpairedOrd()
        val action = pendingToolAction[ord]
        val kind = block.string("kind")
        val content = block["content"] as? JsonObject
        val failed = content?.string("type")?.contains("error") == true ||
            block.string("type")?.contains("error") == true
        val status = if (failed) STATUS_FAILED else STATUS_FINISHED

        when (action ?: if (kind == "read_link") "read_link" else "open_page") {
            "find_in_page" -> fill(
                ord,
                ToolFindPayload(
                    status = status,
                    stageId = stageOf(ord),
                    pattern = pendingToolInput[ord].string("pattern") ?: "",
                ),
            )

            "read_link" -> {
                // signature 在块级，必须与结果一起原样保留（硬约束 3）
                val element = buildJsonObject {
                    content?.forEach { (key, value) -> put(key, value) }
                    block.string("signature")?.let { put("signature", it) }
                    if (content == null) {
                        put("url", pendingToolInput[ord].string("url") ?: "")
                    }
                }
                fill(ord, ReadLinkPayload(results = JsonArray(listOf(element)), call = pendingToolCall[ord]))
            }

            else -> {
                val inner = content?.get("content") as? JsonObject
                fill(
                    ord,
                    ToolOpenPayload(
                        status = status,
                        stageId = stageOf(ord),
                        url = content.string("url") ?: pendingToolInput[ord].string("url") ?: "",
                        title = inner.string("title") ?: content.string("title") ?: "",
                        snippet = content.string("snippet"),
                        siteName = content.string("site_name"),
                        publishedAt = content.string("published_at"),
                        siteIcon = content.string("site_icon"),
                    ),
                )
            }
        }
    }

    /** 结果块分发：调用块先到与乱序到达都走这里 */
    private fun dispatchResult(root: JsonObject, block: JsonObject) {
        when (block.string("type")) {
            "web_search_tool_result" -> onSearchResult(root, block)
            "web_fetch_tool_result" -> onFetchResult(root, block)
            else -> unknownEvents += "result:${block.string("type")}"
        }
    }

    private fun onBlockDelta(root: JsonObject) {
        val index = root.int("index") ?: return
        val ord = slotByBlockIndex[index] ?: return
        val delta = root["delta"] as? JsonObject ?: return

        when (delta.string("type")) {
            "thinking_delta" -> appendThinking(ord, delta.string("thinking") ?: "")
            "text_delta" -> appendText(ord, delta.string("text") ?: "")
            "signature_delta" -> onSignature(ord, delta.string("signature") ?: "")
            "citations_delta" -> appendCitation(ord, delta["citation"])
            "input_json_delta" -> unknownEvents += "input_json_delta@$index"
            else -> unknownEvents += "delta:${delta.string("type")}"
        }
    }

    /**
     * `signature_delta` 表示这一段思考结束时的签名。
     *
     * 签名是对整块内容签的，**截断后回传必然不匹配**，所以它只能用于「整块回放」，
     * 不能用来从中间续写（用户问过这个问题，结论记在 02 §5.1）。
     */
    private fun onSignature(ord: Int, signature: String) {
        val buffer = signatures.getOrPut(ord) { StringBuilder() }
        buffer.append(signature)
        thinkingSignature = buffer.toString()
    }

    private fun onBlockStop(root: JsonObject) {
        val index = root.int("index") ?: return
        val ord = slotByBlockIndex[index] ?: return
        val payload = slots[ord]?.payload as? ThinkPayload ?: return
        // 思维链耗时：起始到 stop 的墙钟差
        val startedAt = slots[ord]!!.startedAt
        val elapsed = (clock() - startedAt) / 1000.0
        fill(ord, payload.copy(elapsedSecs = elapsed))
        // 界面靠这一项把标签从「正在思考」翻成「已思考（用时 N 秒）」，不用等落库
        liveQueue += LiveDelta.ThinkStop(elapsed)
    }

    private fun onMessageDelta(root: JsonObject) {        val delta = root["delta"] as? JsonObject
        delta?.string("stop_reason")?.let { stopReason = StopReason.fromWire(it) }
        delta?.string("title")?.let { title = it }
        (delta?.get("stop_details") as? JsonObject)?.string("type")?.let { stopDetailsType = it }
        // 用量以最后一个 message_delta 为准，不与 message_start 累加
        (root["usage"] as? JsonObject)?.let { usage = it.toUsage() }
    }

    private fun onError(root: JsonObject) {
        val e = root["error"] as? JsonObject
        error = StreamError(type = e.string("type"), message = e.string("message"))
        (root["usage"] as? JsonObject)?.let { usage = it.toUsage() }
    }

    // —— 内部 ——

    private val toolToId = HashMap<Int, String>()

    private fun newSlot(payload: FragmentPayload?): Slot {
        val slot = Slot(ord = slots.size, startedAt = clock(), payload = payload)
        slots += slot
        return slot
    }

    /** 结果块先到时，给它一个占位 ord（后面若调用块到了仍按各自 ord 输出） */
    private fun nextUnpairedOrd(): Int = slots.size

    private fun fill(ord: Int, payload: FragmentPayload) {
        while (slots.size <= ord) slots += null
        val existing = slots[ord]
        slots[ord] = if (existing == null) {
            Slot(ord = ord, startedAt = clock(), payload = payload)
        } else {
            existing.payload = payload
            existing
        }
    }

    private fun appendThinking(ord: Int, delta: String) {
        if (delta.isEmpty()) return
        val current = slots[ord]?.payload as? ThinkPayload ?: ThinkPayload()
        fill(ord, current.copy(content = current.content + delta))
        liveQueue += LiveDelta.Think(delta)
    }

    private fun appendText(ord: Int, delta: String) {
        if (delta.isEmpty()) return
        val current = slots[ord]?.payload as? ResponsePayload ?: ResponsePayload()
        fill(ord, current.copy(content = current.content + delta))
        liveQueue += LiveDelta.Text(delta)
    }

    private fun appendCitation(ord: Int, citation: JsonElement?) {
        val value = citation ?: return
        val current = slots[ord]?.payload as? ResponsePayload ?: ResponsePayload()
        val list = (current.citations ?: JsonArray(emptyList())).toMutableList()
        list += value
        fill(ord, current.copy(citations = JsonArray(list)))
    }

    /** 推理轮次：以已出现的思考块数量近似（影响搜索卡片分组，M3-4 再用） */
    private fun stageOf(ord: Int): Int = thinkingBlocks.coerceAtLeast(1)

    /** 流结束：把仍未配对的调用落成碎片（用户中途停止时就是这种形态） */
    override fun finishUnpairedTools() {
        // 先补上「调用块始终没到」的孤儿结果
        orphanResults.forEach { (toolId, pair) ->
            val slot = newSlot(null)
            slotByToolId[toolId] = slot.ord
            dispatchResult(pair.first, pair.second)
        }
        orphanResults.clear()

        pendingToolAction.forEach { (ord, action) ->
            if (slots[ord]?.payload != null) return@forEach
            val input = pendingToolInput[ord] ?: JsonObject(emptyMap())
            fill(
                ord,
                when (action) {
                    "search" -> ToolSearchPayload(
                        status = STATUS_WIP,
                        stageId = stageOf(ord),
                        queries = (input["queries"] as? JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            ?: emptyList(),
                    )

                    "find_in_page" -> ToolFindPayload(
                        status = STATUS_WIP,
                        stageId = stageOf(ord),
                        pattern = input.string("pattern") ?: "",
                    )

                    "read_link" -> ReadLinkPayload(results = null)

                    else -> ToolOpenPayload(
                        status = STATUS_WIP,
                        stageId = stageOf(ord),
                        url = input.string("url") ?: "",
                        title = "",
                    )
                },
            )
        }
    }

    companion object {
        const val STATUS_FINISHED = "FINISHED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_WIP = "WIP"

        private val json = Json { ignoreUnknownKeys = true }
    }
}

// —— JSON 取值小工具（缺字段一律返回 null，不抛） ——

private fun JsonObject?.string(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject?.int(key: String): Int? = string(key)?.toIntOrNull()

private fun JsonObject.toUsage(): Usage {
    val details = this["output_tokens_details"] as? JsonObject
    val tools = this["server_tool_use"] as? JsonObject
    return Usage(
        promptTokens = int("input_tokens") ?: 0,
        cacheHitTokens = int("cache_read_input_tokens") ?: 0,
        completionTokens = int("output_tokens") ?: 0,
        thinkingTokens = details.int("thinking_tokens") ?: 0,
        safetyTokens = details.int("safety_tokens") ?: 0,
        searchCalls = tools.int("web_search_requests") ?: 0,
        fetchCalls = tools.int("web_fetch_requests") ?: 0,
        imageSearchCalls = tools.int("tool_image_search") ?: 0,
    )
}
