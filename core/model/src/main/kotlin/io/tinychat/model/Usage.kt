package io.tinychat.model

/**
 * 一次请求的用量。字段名对齐服务端 `usage` 对象（02 §4.7）。
 *
 * 铁律：以**最后一个** `message_delta.usage` 为准，不与 `message_start` 累加。
 */
data class Usage(
    /** input_tokens（未命中缓存的部分） */
    val promptTokens: Int = 0,
    /** cache_read_input_tokens */
    val cacheHitTokens: Int = 0,
    /** output_tokens */
    val completionTokens: Int = 0,
    /** output_tokens_details.thinking_tokens */
    val thinkingTokens: Int = 0,
    val safetyTokens: Int = 0,
    /** server_tool_use(action=search) 次数 */
    val searchCalls: Int = 0,
    /** server_tool_use(action=open_page) 次数 */
    val fetchCalls: Int = 0,
    val imageSearchCalls: Int = 0,
) {
    val totalInput: Int get() = promptTokens + cacheHitTokens

    /** 这一轮的总 token（命中 + 未命中 + 输出）：每轮用量行与明细卡的第一行用它（决策 86） */
    val totalTokens: Long get() = totalInput.toLong() + completionTokens

    /** 上下文圆环的分子 */
    val contextUsed: Int get() = totalInput + completionTokens

    val isEmpty: Boolean
        get() = promptTokens == 0 && cacheHitTokens == 0 && completionTokens == 0 &&
            safetyTokens == 0 && searchCalls == 0 && fetchCalls == 0 && imageSearchCalls == 0

    companion object {
        val ZERO = Usage()
    }
}

/**
 * 错误分类（02 §4.5）。`wire` 落库，避免枚举名与导出/日志耦合。
 */
enum class ErrorKind(val wire: String) {
    /** 连接失败 / DNS / 超时 */
    NETWORK("network"),
    /** Key 无效 */
    HTTP_401("http.401"),
    /** 余额不足 */
    HTTP_402("http.402"),
    /** 请求体不合法 */
    HTTP_422("http.422"),
    /**
     * 上下文超限：客户端按 95% 预判，或服务端的 400（`prompt is too long` 一类）。
     *
     * 两个来源共用这一条，用户看到的是同一句话——不管是提前拦住还是真被服务端拒了，
     * 出路都是「开个新对话」。不可重试：再发一次结果一样。
     */
    CONTEXT_LIMIT("contextLimit"),
    /** 并发 / 限流 */
    HTTP_429("http.429"),
    HTTP_5XX("http.5xx"),
    /** 流中 error 帧 */
    STREAM("stream"),
    /** 流提前断开且无 stop_reason */
    INTERRUPTED("interrupted"),
    /** stop_reason=refusal */
    REFUSAL("refusal"),
    UPLOAD("upload"),
    /** 服务端判 file_id 失效 */
    FILE_INVALID("fileInvalid"),
    ;

    /** 是否允许自动重试（refusal / 401 / 402 / 422 一律不允许） */
    val retryable: Boolean
        get() = when (this) {
            NETWORK, HTTP_429, HTTP_5XX -> true
            else -> false
        }

    /**
     * 给用户看的一句话（02 §4.5 表格逐条对齐；2026-11-21 文案评审定稿）。
     *
     * 文案只描述**发生了什么、下一步怎么办**，不暴露 HTTP 码与技术细节。
     */
    val userMessage: String
        get() = userMessage(null)

    /**
     * 同上，但「服务繁忙」要带上服务商名——不点名的话，用户会以为是 App 自己坏了。
     * 拿不到名字（比如后台任务、通知）时退回不带名字的版本。
     */
    fun userMessage(providerName: String? = null): String = when (this) {
        NETWORK -> "网络连接失败"
        HTTP_401 -> "API Key 无效，请在设置中更新"
        HTTP_402 -> "账户余额不足，充值后即可继续"
        HTTP_422 -> "请求异常（内部错误）"
        CONTEXT_LIMIT -> "当前对话已达上限，请开启新对话继续。"
        HTTP_429 -> "操作太频繁，稍后再试"
        HTTP_5XX -> providerName?.takeIf { it.isNotBlank() }
            ?.let { "$it 服务繁忙，稍后再试" }
            ?: "服务繁忙，稍后再试"

        STREAM -> "生成意外中断"
        INTERRUPTED -> "已停止生成"
        REFUSAL -> "暂时无法回答，换个说法试试"
        UPLOAD -> "上传失败，点击重试"
        FILE_INVALID -> "附件已失效，请重新上传"
    }

    /**
     * 行内重试提示（贴在消息下方右侧的灰字）。
     *
     * [detail] 是底层真实原因（「请求超时：超过 120 秒没有收到数据」「域名解析失败」这类）。
     * 网络类错误有详情就用详情——以前一律显示「请检查网络后重试」，
     * 用户和我们都没法区分是超时、DNS 还是根本没连上。
     */
    fun retryHint(detail: String? = null): String {
        val specific = detail?.trim().orEmpty()
        if (this == NETWORK && specific.isNotEmpty()) return specific
        return when (this) {
            NETWORK -> "请检查网络后重试"
            HTTP_429 -> "操作太频繁，稍后再试"
            HTTP_5XX -> "服务繁忙，稍后再试"
            else -> userMessage
        }
    }

    /** 自动重试上限：限流只补一次，服务端故障/网络抖动最多两次 */
    val maxAutoRetries: Int
        get() = when (this) {
            HTTP_429 -> 1
            NETWORK, HTTP_5XX -> 2
            else -> 0
        }

    companion object {
        fun fromWire(value: String?): ErrorKind? = entries.firstOrNull { it.wire == value }
    }
}
