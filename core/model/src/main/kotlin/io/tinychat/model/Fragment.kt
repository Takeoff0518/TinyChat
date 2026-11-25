package io.tinychat.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 碎片类型。与 v3 导出枚举完全一致 —— 新增/改名必须同步导入导出映射，
 * 否则往返丢数据（02 §3.2）。
 *
 * `CONTENT_RISK` 故意不在枚举里：实测 4411 条全是同一句「AI 生成仅供参考」，
 * 属于 UI 提示条而非内容，导入时直接丢弃（01 §2.6）。
 */
enum class FragmentType(val wire: String) {
    REQUEST("REQUEST"),
    RESPONSE("RESPONSE"),
    THINK("THINK"),
    TEMPLATE_RESPONSE("TEMPLATE_RESPONSE"),
    FILE("FILE"),
    TOOL_SEARCH("TOOL_SEARCH"),
    TOOL_OPEN("TOOL_OPEN"),
    TOOL_FIND("TOOL_FIND"),
    TOOL_IMAGE_SEARCH("TOOL_IMAGE_SEARCH"),
    READ_LINK("READ_LINK"),

    /** 未识别类型：payload 转成 UnknownPayload 原样保留，导出时按原字符串写回 */
    UNKNOWN(""),
    ;

    companion object {
        fun fromWire(value: String?): FragmentType =
            entries.firstOrNull { it.wire == value && value.isNotEmpty() } ?: UNKNOWN
    }
}

/**
 * 碎片。`ord` 是消息内顺序，`(message_id, ord)` 唯一。
 *
 * payload 的 JSON 形状**与导出 v3 逐字段同构**（type 字段由编解码器补），
 * 这样导入导出天然可逆，不用写两套转换（00 §4.3 / 01 §2.6）。
 */
data class Fragment(
    val id: Long = 0,
    val messageId: Long,
    val ord: Int,
    val payload: FragmentPayload,
) {
    val type: FragmentType get() = FragmentType.fromWire(payload.typeWire)
}

/**
 * 碎片载荷。`typeWire` 同时决定 `fragments.type` 列与导出 JSON 的 `type` 字段。
 */
sealed interface FragmentPayload {
    val typeWire: String
}

/** 用户提问（v3: `{type, content}`） */
@Serializable
data class RequestPayload(
    val content: String = "",
) : FragmentPayload {
    override val typeWire: String get() = FragmentType.REQUEST.wire
}

/** 正文（v3: `{type, content}`；citation 增量另存 citations） */
@Serializable
data class ResponsePayload(
    val content: String = "",
    /** 引用角标数据，形状未在真实样本中出现过，按原始 JSON 保留 */
    val citations: JsonArray? = null,
) : FragmentPayload {
    override val typeWire: String get() = FragmentType.RESPONSE.wire
}

/** 思维链（v3: `{type, content, elapsed_secs}`） */
@Serializable
data class ThinkPayload(
    val content: String = "",
    @SerialName("elapsed_secs") val elapsedSecs: Double? = null,
) : FragmentPayload {
    override val typeWire: String get() = FragmentType.THINK.wire
}

/** 服务端模板话术（v3: `{type, content}`）；**不得进入上下文** */
@Serializable
data class TemplateResponsePayload(
    val content: String = "",
) : FragmentPayload {
    override val typeWire: String get() = FragmentType.TEMPLATE_RESPONSE.wire
}

/** 附件元数据（v3: `{type, files[]}`） */
@Serializable
data class FilePayload(
    val files: List<FileRef> = emptyList(),
) : FragmentPayload {
    override val typeWire: String get() = FragmentType.FILE.wire
}

@Serializable
data class FileRef(
    val id: String = "",
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_size") val fileSize: Long = 0,
    @SerialName("token_usage") val tokenUsage: Int? = null,
    /** v3 里是 ISO 字符串，如 `2026-10-26T12:39:00.446Z` */
    @SerialName("inserted_at") val insertedAt: String? = null,
    @SerialName("export_path") val exportPath: String? = null,
    @SerialName("is_image") val isImage: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
)

/** 联网搜索（v3: `{type, status, stage_id, queries[], results[]}`） */
@Serializable
data class ToolSearchPayload(
    val status: String = "",
    @SerialName("stage_id") val stageId: Int? = null,
    val queries: List<String> = emptyList(),
    val results: List<SearchResult> = emptyList(),
    /**
     * 失败原因（只有 status=FAILED 时可能有）：把底层异常的一句话说给用户听，
     * 否则界面上永远只有「联网搜索暂不可用」，看诊无从下手。
     * 旧库里的载荷没有这个字段，默认 null，兼容。
     */
    val reason: String? = null,
) : FragmentPayload {
    override val typeWire: String get() = FragmentType.TOOL_SEARCH.wire
}

@Serializable
data class SearchResult(
    val url: String = "",
    val title: String = "",
    val snippet: String? = null,
    /** ISO 字符串，如 `2025-10-22T16:00:00Z` */
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("site_icon") val siteIcon: String? = null,
    @SerialName("site_name") val siteName: String? = null,
)

/** 边想边搜打开的页面（v3: 扁平字段，**无正文**） */
@Serializable
data class ToolOpenPayload(
    val status: String = "",
    @SerialName("stage_id") val stageId: Int? = null,
    val url: String = "",
    val title: String = "",
    val snippet: String? = null,
    @SerialName("site_name") val siteName: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("site_icon") val siteIcon: String? = null,
) : FragmentPayload {
    override val typeWire: String get() = FragmentType.TOOL_OPEN.wire
}

/** 页内查找（v3: `{type, status, stage_id, pattern}`） */
@Serializable
data class ToolFindPayload(
    val status: String = "",
    @SerialName("stage_id") val stageId: Int? = null,
    val pattern: String = "",
) : FragmentPayload {
    override val typeWire: String get() = FragmentType.TOOL_FIND.wire
}

/** 图搜（真实样本里 results 恒为空数组，按原始 JSON 保留） */
@Serializable
data class ToolImageSearchPayload(
    val status: String = "",
    @SerialName("stage_id") val stageId: Int? = null,
    val results: JsonArray? = null,
) : FragmentPayload {
    override val typeWire: String get() = FragmentType.TOOL_IMAGE_SEARCH.wire
}

/**
 * 阅读链接（v3: `{type, results[]}`）。
 *
 * ⚠️ `results[].signature` **必须原样回传**，否则多轮丢网页上下文（02 §4.3）。
 * 真实样本里 3 条全是空数组，字段形状无法实测，因此整块按原始 JSON 保留，
 * 保证「取到什么样、发回什么样」。
 */
@Serializable
data class ReadLinkPayload(
    val results: JsonArray? = null,
    /**
     * 调用侧 `server_tool_use` 整块（`id` / `name` / `input` / `caller` / `kind`）。
     *
     * 回放历史时必须与结果块成对发回去，否则服务端丢网页上下文。
     * **本地字段**：v3 导出只写 `results`，这里的内容不进导出文件（M6 导出实现照此）。
     */
    val call: JsonObject? = null,
) : FragmentPayload {
    override val typeWire: String get() = FragmentType.READ_LINK.wire
}

/**
 * 未识别类型的安全网：保留原始 type 字符串与整块 JSON，导出时原样写回。
 */
@Serializable
data class UnknownPayload(
    /** 原始 type 值（不能用 FragmentType.UNKNOWN 的空串） */
    val wireType: String,
    val raw: JsonObject,
) : FragmentPayload {
    override val typeWire: String get() = wireType
}
