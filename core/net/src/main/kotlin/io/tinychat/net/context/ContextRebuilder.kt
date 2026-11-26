package io.tinychat.net.context

import io.tinychat.model.Fragment
import io.tinychat.model.Message
import io.tinychat.model.MessageStatus
import io.tinychat.model.ReadLinkPayload
import io.tinychat.model.RequestPayload
import io.tinychat.model.ResponsePayload
import io.tinychat.model.Role
import io.tinychat.model.TemplateResponsePayload
import io.tinychat.net.protocol.AnthropicMessagesProtocol
import io.tinychat.net.protocol.DeepSeekMessagesProtocol
import io.tinychat.net.protocol.WireMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * 历史轮次里的一个附件：只带重建请求需要的东西。
 *
 * - `remoteId`：官方 Files API 的 file_id，为 null = 没上传成功（导入的老数据、或上传失败），**不进请求**；
 * - `dataUri`：第三方协议用的内联图片或 PDF；`text` 是在本机读取的文件文字。
 * - 若该轮正文也为空、又没有可用的附件，整轮会被丢弃（与「孤立 USER」同处理）。
 */
data class HistoryAttachment(
    val remoteId: String?,
    val isImage: Boolean,
    /** 第三方的内联图片或 PDF；官方链路不用它 */
    val dataUri: String? = null,
    val fileName: String? = null,
    val mime: String? = null,
    val text: String? = null,
) {
    /** 有没有东西能进请求（具体形状由 [ImageFlavor] 决定） */
    val usable: Boolean get() = !remoteId.isNullOrBlank() || !dataUri.isNullOrBlank() || text != null
}

internal fun HistoryAttachment.documentTextBlock(): JsonObject? {
    val content = text?.takeIf { !isImage && it.isNotBlank() } ?: return null
    val name = fileName.orEmpty().ifBlank { "附件" }
    return buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive("[附件 $name]\n$content\n[/附件]\n\n"))
    }
}

/**
 * 各协议的附件块。
 *
 * - `DEEPSEEK`：官方 Files API，`{"type":"image","source":{"type":"file","file_id":…}}`；
 * - `OPENAI`：内联 `image_url`、PDF 文件块和文本内容；
 * - `ANTHROPIC`：Base64 `image`、Base64 PDF 和文本内容。
 * 第三方协议的内联附件从本地文件重建。
 */
enum class ImageFlavor { DEEPSEEK, OPENAI, ANTHROPIC }

/** 上下文构建的一段：消息 + 它的碎片（已按 ord 排好）+ 它的附件。 */
data class ContextTurn(
    val message: Message,
    val fragments: List<Fragment>,
    val attachments: List<HistoryAttachment> = emptyList(),
)

/**
 * 上下文重建（M1-10）。**这是最容易出错的地方**，规则逐条对齐 02 §5.2：
 *
 * 1. 只沿当前分支线性回溯（由调用方保证顺序：root → leaf）；
 * 2. 只保留「USER + 可用 ASSISTANT」成对的轮次；孤立 USER、不可用 ASSISTANT 整轮跳过；
 * 3. 「可用」= 状态属于 FINISHED/INCOMPLETE、不是拒答、不含服务端模板块、正文非空；
 * 4. ASSISTANT 回传**正文**；历史思考一律不回传（继续生成是例外，见 M1-9），
 *    且 `read_link` 要把**调用块 + 结果块（含 signature）**成对回传，否则多轮丢网页上下文；
 * 5. 历史里的 `latest_reminder` 用**那一轮自己的快照**，且只在与上一条已插入的不同时插入；
 *    开关关闭时（本轮 reminder 为 null）历史快照也一条都不发；
 * 6. 末尾追加本轮的 `latest_reminder`（与最后一条历史不同才插），本轮的 USER 由调用方追加
 *    （含附件块，文件在前、文本在后）。
 *
 * **规则 5 为什么不能用「今天的日期」重刷历史**（2026-11 修）：服务端的上下文缓存按前缀
 * 命中，历史里任何一处变了，从那里往后全部重新计费。早期实现拿 `currentReminder` 跟每条
 * 历史快照比，日期一跨天，所有历史轮次**每条前面都插一条今天的 reminder** —— 既让整段
 * 前缀作废（隔天第一条消息就是全量重算），又多塞 N 行重复日期。真实行为见参考实现
 * `chat-demo-ref/src/services/chat/reply.ts` 的 `appendReminder`：历史用它自己那条，
 * 只有末尾的本轮 reminder 是新的 —— 这样除末尾外整段报文逐字节不变，缓存能一直吃到
 * 最后一个字。没有快照的老数据（导入的会话）就照原样不带，不拿今天的日期去补。
 */
object ContextRebuilder {

    fun build(
        branch: List<ContextTurn>,
        currentReminder: String?,
        flavor: ImageFlavor = ImageFlavor.DEEPSEEK,
    ): List<WireMessage> {
        val out = ArrayList<WireMessage>(branch.size + 2)

        var pendingUser: ContextTurn? = null
        // 最近一次插进去的 reminder 原文：只有变化时才插，避免同一天刷一长串
        var lastReminder: String? = null
        // 开关关掉（本轮 reminder 为 null）时，历史里的快照也一条都不发 —— 用户要的是「完全不发」
        val remindersEnabled = currentReminder != null

        fun emitReminder(reminder: String?) {
            if (!remindersEnabled || reminder == null || reminder == lastReminder) return
            out += WireMessage.text(WireMessage.ROLE_REMINDER, reminder)
            lastReminder = reminder
        }

        for (turn in branch) {
            when (turn.message.role) {
                Role.USER -> {
                    // 上一轮 USER 没等到可用回复 —— 整轮丢弃（规则 2）
                    pendingUser = turn
                }

                Role.ASSISTANT -> {
                    val user = pendingUser ?: continue
                    // 拒答那一轮不进上下文（决策 85）：模型说的拒绝话术不是答案，回传会污染历史，
                    // 在 Anthropic 那边还可能撞上「末尾不能是 assistant 预填」的规则。
                    // 以前是靠「拒答时把正文删掉」让这里自然落空，现在正文要留着展示，
                    // 就必须显式剔除；提问一起丢掉，保持 user/assistant 交替。
                    if (turn.message.status == MessageStatus.CONTENT_FILTER) {
                        pendingUser = null
                        continue
                    }
                    val assistantText = turn.usableText() ?: continue

                    val userText = user.userText().orEmpty()
                    val attachments = user.usableAttachments(flavor)
                    // 正文和附件都空 = 这一轮没有内容可回传
                    if (userText.isBlank() && attachments.isEmpty()) {
                        pendingUser = null
                        continue
                    }

                    // 规则 5：用这条 USER 自己的快照（历史不可改，否则前缀缓存全废）
                    emitReminder(user.message.reminderSnapshot)
                    out += userMessage(userText, attachments, flavor)
                    out += if (flavor == ImageFlavor.ANTHROPIC) {
                        WireMessage.text(WireMessage.ROLE_ASSISTANT, assistantText)
                    } else {
                        turn.assistantMessage(assistantText)
                    }
                    pendingUser = null
                }
            }
        }

        // 规则 6：本轮的位置在末尾，调用方紧接着追加本轮的 USER
        emitReminder(currentReminder)

        return out
    }

    /**
     * 规则 6 / 附件：**图片与文件在前、文本在后**（03 §6.5 的示例顺序）。
     *
     * 没有可用附件时退化成纯文本消息，报文与 M4 之前完全一致 —— 老会话不会因为
     * 这次改动多出空 `content` 数组。
     */
    fun userMessage(
        text: String,
        attachments: List<HistoryAttachment>,
        flavor: ImageFlavor = ImageFlavor.DEEPSEEK,
    ): WireMessage {
        val usable = attachments.filter { it.usable }
        if (usable.isEmpty()) return WireMessage.text(WireMessage.ROLE_USER, text)

        val blocks = buildList {
            usable.forEach { attachment ->
                when (flavor) {
                    ImageFlavor.DEEPSEEK -> {
                        val id = attachment.remoteId ?: return@forEach
                        add(
                            if (attachment.isImage) {
                                DeepSeekMessagesProtocol.imageBlock(id)
                            } else {
                                DeepSeekMessagesProtocol.fileBlock(id)
                            },
                        )
                    }
                    ImageFlavor.OPENAI -> openAiAttachmentBlock(attachment)?.let { add(it) }
                    ImageFlavor.ANTHROPIC -> AnthropicMessagesProtocol.attachmentBlock(attachment)?.let { add(it) }
                }
            }
            if (text.isNotBlank()) {
                add(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(text))
                    },
                )
            }
        }
        // 附件一个块都拼不出来（例如 encode 失败）：退回纯文本，别发空 content
        if (blocks.isEmpty()) return WireMessage.text(WireMessage.ROLE_USER, text)
        return WireMessage.blocks(WireMessage.ROLE_USER, blocks)
    }

    /** OpenAI 兼容链路的图片块：`data:` URI 直接内联，历史每轮都要重发 */
    fun openAiImageBlock(dataUri: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("image_url"))
        put(
            "image_url",
            buildJsonObject { put("url", JsonPrimitive(dataUri)) },
        )
    }

    private fun openAiAttachmentBlock(attachment: HistoryAttachment): JsonObject? {
        val uri = attachment.dataUri
        val name = attachment.fileName.orEmpty().ifBlank { "附件" }
        return when {
            attachment.isImage && uri != null -> openAiImageBlock(uri)
            uri != null && attachment.mime == "application/pdf" -> buildJsonObject {
                put("type", JsonPrimitive("file"))
                put("file", buildJsonObject {
                    put("filename", JsonPrimitive(name))
                    put("file_data", JsonPrimitive(uri))
                })
            }
            else -> attachment.documentTextBlock()
        }
    }

    private fun ContextTurn.usableAttachments(flavor: ImageFlavor): List<HistoryAttachment> =
        attachments.filter {
            when (flavor) {
                ImageFlavor.DEEPSEEK -> !it.remoteId.isNullOrBlank()
                ImageFlavor.OPENAI -> !it.dataUri.isNullOrBlank() || it.text != null
                ImageFlavor.ANTHROPIC -> AnthropicMessagesProtocol.acceptsAttachment(it)
            }
        }

    /**
     * 规则 4：没有 `read_link` 时就是一条纯文本消息；有时改成块数组
     * `[正文, 调用块, 结果块, …]`。
     */
    private fun ContextTurn.assistantMessage(text: String): WireMessage {
        val links = fragments.mapNotNull { it.payload as? ReadLinkPayload }.filter { it.results != null }
        if (links.isEmpty()) return WireMessage.text(WireMessage.ROLE_ASSISTANT, text)

        val blocks = buildList {
            add(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(text))
                },
            )
            links.forEach { payload -> addAll(readLinkBlocks(payload)) }
        }
        return WireMessage.blocks(WireMessage.ROLE_ASSISTANT, blocks)
    }

    private fun ContextTurn.userText(): String? {
        val text = fragments
            .filter { it.payload is RequestPayload }
            .joinToString("") { (it.payload as RequestPayload).content }
        return text.takeIf { it.isNotBlank() }
    }

    /** 规则 3 + 4：可用才返回正文 */
    private fun ContextTurn.usableText(): String? {
        if (!message.status.isUsableInContext) return null
        if (fragments.any { it.payload is TemplateResponsePayload }) return null

        val text = fragments
            .filter { it.payload is ResponsePayload }
            .joinToString("") { (it.payload as ResponsePayload).content }

        return text.takeIf { it.isNotBlank() }
    }

    /** 供调用方判断：这条回复能不能进上下文（M1-10 规则 3 的对外形态） */
    fun isUsable(message: Message, fragments: List<Fragment>): Boolean {
        if (!message.status.isUsableInContext) return false
        if (fragments.any { it.payload is TemplateResponsePayload }) return false
        return fragments.any { (it.payload as? ResponsePayload)?.content?.isNotBlank() == true }
    }

    /** 终止状态是否算「正常结束」 */
    fun isFinished(status: MessageStatus): Boolean = status == MessageStatus.FINISHED

    /**
     * 一条 `read_link` 的一对块：调用块原样回传，结果块补回 `tool_use_id` 与 `signature`。
     *
     * 结果碎片里存的是 content 本体 + signature（见 MessageAssembler），所以要拆开重装；
     * 失败结果没有 signature，只回传结果块（服务端认这个形状）。
     */
    private fun readLinkBlocks(payload: ReadLinkPayload): List<JsonObject> {
        val result = payload.results?.firstOrNull() as? JsonObject ?: return emptyList()
        val signature = (result["signature"] as? JsonPrimitive)?.contentOrNull
        val callId = (payload.call?.get("id") as? JsonPrimitive)?.contentOrNull
        val toolUseId = callId ?: (result["tool_use_id"] as? JsonPrimitive)?.contentOrNull
        val kind = (payload.call?.get("kind") as? JsonPrimitive)?.contentOrNull

        val callBlock = payload.call?.let { call ->
            buildJsonObject { call.forEach { (key, value) -> put(key, value) } }
        }

        val content = JsonObject(result.filterKeys { it != "signature" && it != "tool_use_id" })

        val resultBlock = buildJsonObject {
            put("type", JsonPrimitive("web_fetch_tool_result"))
            if (toolUseId != null) put("tool_use_id", JsonPrimitive(toolUseId))
            put("content", content)
            if (kind != null) put("kind", JsonPrimitive(kind))
            if (signature != null) put("signature", JsonPrimitive(signature))
        }

        return listOfNotNull(callBlock, resultBlock)
    }
}
