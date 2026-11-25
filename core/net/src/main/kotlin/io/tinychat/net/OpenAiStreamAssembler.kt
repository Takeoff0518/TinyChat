package io.tinychat.net

import io.tinychat.model.Fragment
import io.tinychat.model.ResponsePayload
import io.tinychat.model.ThinkPayload
import io.tinychat.model.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * OpenAI Chat Completions 的流解码器（M8-1）。
 *
 * 输入是 `data: {...}` 帧（SSE 的 event 名恒为 null），输出与 [MessageAssembler] 同构的碎片：
 * `reasoning_content` → THINK，`content` → RESPONSE。
 *
 * 和官方不同，这里**没有工具块、没有思考签名、没有服务端标题**：
 * 能力位关闭时上层本来就不会要这些东西，标题改由仓库本地生成。
 */
class OpenAiStreamAssembler : StreamAssembler {

    override var stopReason: StopReason? = null
        private set
    override var title: String? = null
        private set
    override var usage: Usage? = null
        private set
    override var error: StreamError? = null
        private set
    override var sawMessageStop: Boolean = false
        private set
    override val thinkingSignature: String? get() = null

    private var think: StringBuilder? = null
    private var response: StringBuilder? = null
    private var thinkOrd: Int? = null
    private var responseOrd: Int? = null
    private var nextOrd = 0

    /** 未识别帧，仅用于诊断；不抛异常 */
    val unknownFrames: MutableList<String> = mutableListOf()

    override fun onFrame(name: String?, data: String) {
        val payload = data.trim()
        if (payload.isEmpty()) return
        if (payload == DONE) {
            sawMessageStop = true
            return
        }

        val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
        if (root == null) {
            unknownFrames += "非 JSON 帧"
            return
        }

        (root["error"] as? JsonObject)?.let { obj ->
            error = StreamError(
                type = obj["type"].asText() ?: obj["code"].asText(),
                message = obj["message"].asText() ?: "服务商返回错误",
            )
            return
        }

        // 末帧通常只有 usage；先读用量，再读 choices
        (root["usage"] as? JsonObject)?.let { usage = it.toUsage() }

        val choice = root["choices"]?.jsonArray?.firstOrNull() as? JsonObject ?: return
        (choice["delta"] as? JsonObject)?.let { delta ->
            delta["reasoning_content"].asText()?.let { appendThink(it) }
            delta["reasoning"].asText()?.let { appendThink(it) }
            delta["content"].asText()?.let { appendResponse(it) }
        }
        choice["finish_reason"].asText()?.let { finish ->
            stopReason = stopReasonOf(finish)
            sawMessageStop = true
        }
    }

    /** 本帧产生的实时增量，交给上层逐帧出字；读完即清 */
    private val liveQueue = ArrayList<LiveDelta>(2)

    override fun drainLive(into: (LiveDelta) -> Unit) {
        for (delta in liveQueue) into(delta)
        liveQueue.clear()
    }

    override fun fragments(): List<Fragment> {
        val list = ArrayList<Fragment>(2)
        thinkOrd?.let { ord -> think?.let { list += Fragment(messageId = 0, ord = ord, payload = ThinkPayload(it.toString())) } }
        responseOrd?.let { ord -> response?.let { list += Fragment(messageId = 0, ord = ord, payload = ResponsePayload(it.toString())) } }
        return list.sortedBy { it.ord }
    }

    override fun finishUnpairedTools() = Unit

    override fun usedServerTools(): Boolean = false

    /** 这一段思考是否已经报过「结束」；正文一动就报，思考又来了就复位 */
    private var thinkingStopped = false

    private fun appendThink(text: String) {
        if (text.isEmpty()) return
        val builder = think ?: StringBuilder().also {
            think = it
            thinkOrd = nextOrd++
            liveQueue += LiveDelta.ThinkStart
        }
        builder.append(text)
        thinkingStopped = false
        liveQueue += LiveDelta.Think(text)
    }

    private fun appendResponse(text: String) {
        if (text.isEmpty()) return
        val builder = response ?: StringBuilder().also {
            response = it
            responseOrd = nextOrd++
        }
        // OpenAI 兼容链路没有 content_block_stop：正文一动就说明这一段思考写完了。
        // 真到了收尾还没正文（只剩思考）时由上层按「思考里的正文为空」兜住。
        if (think != null && !thinkingStopped) {
            thinkingStopped = true
            liveQueue += LiveDelta.ThinkStop()
        }
        builder.append(text)
        liveQueue += LiveDelta.Text(text)
    }

    private fun stopReasonOf(finish: String): StopReason = when (finish) {
        "stop" -> StopReason.END_TURN
        "length" -> StopReason.MAX_TOKENS
        "content_filter" -> StopReason.REFUSAL
        "tool_calls", "function_call" -> StopReason.TOOL_USE
        else -> StopReason.UNKNOWN
    }

    private fun JsonObject.toUsage(): Usage {
        val prompt = this["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val completion = this["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cached = (this["prompt_tokens_details"] as? JsonObject)
            ?.get("cached_tokens")?.jsonPrimitive?.intOrNull
            ?: this["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull
            ?: 0
        // OpenAI 的 prompt_tokens 含缓存命中；内部口径是「未命中」与「命中」分开，所以减掉
        return Usage(
            promptTokens = (prompt - cached).coerceAtLeast(0),
            cacheHitTokens = cached,
            completionTokens = completion,
        )
    }

    private fun JsonElement?.asText(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private companion object {
        const val DONE = "[DONE]"
        val Json = Json { ignoreUnknownKeys = true }
    }
}
