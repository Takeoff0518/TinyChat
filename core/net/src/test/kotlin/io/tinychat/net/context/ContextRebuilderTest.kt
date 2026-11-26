package io.tinychat.net.context

import io.tinychat.model.Fragment
import io.tinychat.model.Message
import io.tinychat.model.MessageStatus
import io.tinychat.model.RequestPayload
import io.tinychat.model.ReadLinkPayload
import io.tinychat.model.ResponsePayload
import io.tinychat.model.Role
import io.tinychat.model.TemplateResponsePayload
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1-10：上下文重建的六条规则。全部离线，不联网。
 */
class ContextRebuilderTest {

    private val reminder = "2026-11-17,星期一,,App,中文"

    private fun user(id: Long, text: String, snapshot: String? = null) = ContextTurn(
        message = Message(
            id = id,
            sessionId = "s",
            role = Role.USER,
            status = MessageStatus.FINISHED,
            insertedAt = id,
            reminderSnapshot = snapshot,
        ),
        fragments = listOf(Fragment(messageId = id, ord = 0, payload = RequestPayload(text))),
    )

    private fun assistant(
        id: Long,
        text: String,
        status: MessageStatus = MessageStatus.FINISHED,
        extra: List<Fragment> = emptyList(),
    ) = ContextTurn(
        message = Message(
            id = id,
            sessionId = "s",
            parentId = id - 1,
            role = Role.ASSISTANT,
            status = status,
            insertedAt = id,
        ),
        fragments = listOf(Fragment(messageId = id, ord = 0, payload = ResponsePayload(text))) + extra,
    )

    @Test
    fun `read_link 的调用块与结果块成对回传，签名保留`() {
        val call = buildJsonObject {
            put("type", JsonPrimitive("server_tool_use"))
            put("id", JsonPrimitive("srvtoolu_rl_01"))
            put("name", JsonPrimitive("deepseek_web_fetch"))
            put("kind", JsonPrimitive("read_link"))
            put("input", buildJsonObject { put("action", JsonPrimitive("read_link")) })
        }
        val link = Fragment(
            messageId = 2,
            ord = 1,
            payload = ReadLinkPayload(
                results = JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", JsonPrimitive("web_fetch_result"))
                            put("url", JsonPrimitive("https://example.com/a"))
                            put("signature", JsonPrimitive("q3J0b1RmZ1c4cE1x"))
                        },
                    ),
                ),
                call = call,
            ),
        )

        val out = ContextRebuilder.build(
            listOf(user(1, "读一下这个链接"), assistant(2, "读完了", extra = listOf(link))),
            currentReminder = null,
        )

        val assistant = out.last()
        val blocks = (assistant.content as JsonArray).map { it as JsonObject }
        assertEquals(3, blocks.size)
        assertEquals("text", (blocks[0]["type"] as JsonPrimitive).content)
        // 调用块原样回传
        assertEquals("srvtoolu_rl_01", (blocks[1]["id"] as JsonPrimitive).content)
        // 结果块补回 tool_use_id 与签名，且不再把 signature 混在 content 里
        assertEquals("web_fetch_tool_result", (blocks[2]["type"] as JsonPrimitive).content)
        assertEquals("srvtoolu_rl_01", (blocks[2]["tool_use_id"] as JsonPrimitive).content)
        assertEquals("q3J0b1RmZ1c4cE1x", (blocks[2]["signature"] as JsonPrimitive).content)
        assertNull((blocks[2]["content"] as JsonObject)["signature"])
    }

    @Test
    fun `没有 read_link 的轮次仍是纯文本消息`() {
        val out = ContextRebuilder.build(listOf(user(1, "你好"), assistant(2, "你也好")), null)

        assertTrue(out.last().content is JsonPrimitive)
    }

    private fun roles(messages: List<io.tinychat.net.protocol.WireMessage>) = messages.map { it.role }

    @Test
    fun `成对轮次按顺序还原`() {
        val out = ContextRebuilder.build(
            listOf(
                user(1, "第一次"),
                assistant(2, "答一"),
                user(3, "第二次"),
                assistant(4, "答二"),
            ),
            currentReminder = null,
        )

        assertEquals(listOf("user", "assistant", "user", "assistant"), roles(out))
    }

    @Test
    fun `孤立 USER 整轮跳过`() {
        val out = ContextRebuilder.build(
            listOf(user(1, "有回复"), assistant(2, "好"), user(3, "没等到回复")),
            currentReminder = null,
        )
        // 末尾的 USER 由调用方另外追加，这里不应出现
        assertEquals(listOf("user", "assistant"), roles(out))
    }

    @Test
    fun `失败态 ASSISTANT 整轮跳过`() {
        val out = ContextRebuilder.build(
            listOf(user(1, "问题"), assistant(2, "——", status = MessageStatus.FAILED)),
            currentReminder = null,
        )
        assertEquals(emptyList<String>(), roles(out))
    }

    @Test
    fun `拒答轮整轮跳过，模型说的拒绝话不回传（决策 85）`() {
        val out = ContextRebuilder.build(
            listOf(
                user(1, "正常问题"),
                assistant(2, "正常回答"),
                user(3, "敏感问题"),
                assistant(4, "抱歉，这个我没法帮。", status = MessageStatus.CONTENT_FILTER),
                user(5, "下一个问题"),
                assistant(6, "下一个回答"),
            ),
            currentReminder = null,
        )
        // 拒答的正文留在库里展示，但绝不能进上下文；提问一起丢掉，保持 user/assistant 交替
        assertEquals(listOf("user", "assistant", "user", "assistant"), roles(out))
        assertTrue(out.none { it.content.toString().contains("没法帮") })
        assertTrue(out.none { it.content.toString().contains("敏感问题") })
    }

    @Test
    fun `服务端模板块不入上下文`() {
        val out = ContextRebuilder.build(
            listOf(
                user(1, "问题"),
                assistant(
                    2,
                    "你好，这个问题我暂时无法回答",
                    extra = listOf(
                        Fragment(messageId = 2, ord = 1, payload = TemplateResponsePayload("模板话术")),
                    ),
                ),
            ),
            currentReminder = null,
        )
        assertEquals(emptyList<String>(), roles(out))
    }

    @Test
    fun `空正文不入上下文`() {
        val out = ContextRebuilder.build(
            listOf(user(1, "问题"), assistant(2, "   ")),
            currentReminder = null,
        )
        assertEquals(emptyList<String>(), roles(out))
    }

    @Test
    fun `首轮没有历史也要带上当天的 reminder`() {
        val out = ContextRebuilder.build(emptyList(), currentReminder = reminder)

        assertEquals(listOf("latest_reminder"), roles(out))
        assertEquals(reminder, (out.single().content as JsonPrimitive).content)
    }

    @Test
    fun `历史 reminder 用各自快照，只在与上一条不同时插`() {
        val day1 = "2026-11-17,星期一,,App,中文"
        val day2 = "2026-11-18,星期二,,App,中文"
        val out = ContextRebuilder.build(
            listOf(
                user(1, "第一轮", snapshot = day1),
                assistant(2, "答一"),
                user(3, "第二轮", snapshot = day1),   // 与上一条相同 → 不插
                assistant(4, "答二"),
                user(5, "第三轮", snapshot = day2),   // 换了 → 插
                assistant(6, "答三"),
            ),
            currentReminder = day2,                    // 与最后一条相同 → 末尾不再插
        )

        assertEquals(
            listOf(
                "latest_reminder", "user", "assistant",
                "user", "assistant",
                "latest_reminder", "user", "assistant",
            ),
            roles(out),
        )
    }

    @Test
    fun `隔天再聊时历史逐字节不变，只在末尾补一条新 reminder`() {
        val day1 = "2026-11-17,星期一,,App,中文"
        val day2 = "2026-11-18,星期二,,App,中文"
        val branch = listOf(
            user(1, "第一轮", snapshot = day1),
            assistant(2, "答一"),
            user(3, "第二轮", snapshot = day1),
            assistant(4, "答二"),
        )

        val sameDay = ContextRebuilder.build(branch, currentReminder = day1)
        val nextDay = ContextRebuilder.build(branch, currentReminder = day2)

        // 历史部分完全一致 —— 这正是服务端前缀缓存能吃满的前提
        assertEquals(sameDay, nextDay.dropLast(1))
        assertEquals("latest_reminder", nextDay.last().role)
        assertEquals(day2, (nextDay.last().content as JsonPrimitive).content)
    }

    @Test
    fun `没有快照的老数据不拿今天的日期去补`() {
        val out = ContextRebuilder.build(
            listOf(user(1, "导入的问题"), assistant(2, "导入的回答")),
            currentReminder = reminder,
        )

        // 历史那轮什么都不插，今天的日期只出现在末尾
        assertEquals(listOf("user", "assistant", "latest_reminder"), roles(out))
    }

    @Test
    fun `reminder 关闭时一条都不插`() {
        val out = ContextRebuilder.build(
            listOf(user(1, "问题", snapshot = reminder), assistant(2, "答案")),
            currentReminder = null,
        )
        assertEquals(listOf("user", "assistant"), roles(out))
    }

    @Test
    fun `INCOMPLETE 也能进上下文`() {
        val out = ContextRebuilder.build(
            listOf(user(1, "问题", snapshot = reminder), assistant(2, "说到一半", status = MessageStatus.INCOMPLETE)),
            currentReminder = reminder,
        )
        assertEquals(listOf("latest_reminder", "user", "assistant"), roles(out))
    }

    // —— M4 附件（03 §6.5 的块顺序） ——

    private fun userWithAttachments(
        id: Long,
        text: String,
        attachments: List<HistoryAttachment>,
    ) = ContextTurn(
        message = Message(
            id = id,
            sessionId = "s",
            role = Role.USER,
            status = MessageStatus.FINISHED,
            insertedAt = id,
        ),
        fragments = if (text.isBlank()) {
            emptyList()
        } else {
            listOf(Fragment(messageId = id, ord = 0, payload = RequestPayload(text)))
        },
        attachments = attachments,
    )

    @Test
    fun `带图片的提问按 图片块 再 文本块 的顺序回传`() {
        val out = ContextRebuilder.build(
            listOf(
                userWithAttachments(
                    1,
                    "这张图里是什么",
                    listOf(HistoryAttachment(remoteId = "file-image-1", isImage = true)),
                ),
                assistant(2, "是一张图"),
            ),
            currentReminder = null,
        )

        val userContent = out.first { it.role == "user" }.content as JsonArray
        assertEquals(2, userContent.size)
        assertEquals("image", (userContent[0] as JsonObject)["type"].toString().trim('"'))
        assertEquals("text", (userContent[1] as JsonObject)["type"].toString().trim('"'))
        assertEquals(
            "file-image-1",
            (((userContent[0] as JsonObject)["source"] as JsonObject)["file_id"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `文档在前面、文本在最后`() {
        val out = ContextRebuilder.build(
            listOf(
                userWithAttachments(
                    1,
                    "总结这份文档",
                    listOf(
                        HistoryAttachment(remoteId = "file-api-doc", isImage = false),
                        HistoryAttachment(remoteId = "file-image-1", isImage = true),
                    ),
                ),
                assistant(2, "好的"),
            ),
            currentReminder = null,
        )

        val userContent = out.first { it.role == "user" }.content as JsonArray
        assertEquals(listOf("document", "image", "text"), userContent.map {
            ((it as JsonObject)["type"] as JsonPrimitive).content
        })
    }

    @Test
    fun `只有图片没有文字的轮次也要回传`() {
        val out = ContextRebuilder.build(
            listOf(
                userWithAttachments(
                    1,
                    "",
                    listOf(HistoryAttachment(remoteId = "file-image-1", isImage = true)),
                ),
                assistant(2, "图里有三行字"),
            ),
            currentReminder = null,
        )

        assertEquals(listOf("user", "assistant"), roles(out))
        val userContent = out.first().content as JsonArray
        assertEquals(1, userContent.size)
        assertEquals("image", ((userContent[0] as JsonObject)["type"] as JsonPrimitive).content)
    }

    @Test
    fun `附件没拿到 file_id 时不进请求，空轮整轮跳过`() {
        // 上传失败的附件 + 空正文 = 这一轮没有任何可回传的内容
        val out = ContextRebuilder.build(
            listOf(
                userWithAttachments(1, "", listOf(HistoryAttachment(remoteId = null, isImage = true))),
                assistant(2, "不该出现"),
            ),
            currentReminder = null,
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `有正文但附件没上传成功时退化成纯文本`() {
        val out = ContextRebuilder.build(
            listOf(
                userWithAttachments(1, "看这张图", listOf(HistoryAttachment(remoteId = null, isImage = true))),
                assistant(2, "看不到图"),
            ),
            currentReminder = null,
        )

        assertEquals("看这张图", (out.first().content as JsonPrimitive).content)
    }

    @Test
    fun `没有附件时 userMessage 输出纯文本，报文与 M4 之前一致`() {
        val message = ContextRebuilder.userMessage("普通一句", emptyList())
        assertEquals("普通一句", (message.content as JsonPrimitive).content)
    }

    // —— 第三方视觉（决策 53）：图片内联成 image_url，文件一律不发 ——

    @Test
    fun `第三方视觉把图片拼成 image_url 块，图在前文字在后`() {
        val attachments = listOf(
            HistoryAttachment(remoteId = null, isImage = true, dataUri = "data:image/jpeg;base64,AAA"),
        )
        val message = ContextRebuilder.userMessage("这张图", attachments, ImageFlavor.OPENAI)
        val content = message.content as JsonArray

        assertEquals(2, content.size)
        assertEquals("image_url", (content[0] as JsonObject)["type"]!!.jsonPrimitive.content)
        val url = ((content[0] as JsonObject)["image_url"] as JsonObject)["url"]!!.jsonPrimitive.content
        assertEquals("data:image/jpeg;base64,AAA", url)
        assertEquals("text", (content[1] as JsonObject)["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `第三方只发图片，文件与没有 data URI 的图片被丢弃`() {
        val attachments = listOf(
            HistoryAttachment(remoteId = "file_1", isImage = false),
            // 官方 Files API 的历史图片：第三方链路没有 data URI，发不出去
            HistoryAttachment(remoteId = "file_2", isImage = true),
            HistoryAttachment(remoteId = null, isImage = true, dataUri = "data:image/jpeg;base64,BBB"),
        )
        val message = ContextRebuilder.userMessage("看图", attachments, ImageFlavor.OPENAI)
        val content = message.content as JsonArray

        assertEquals("一张图 + 一段文字", 2, content.size)
    }

    @Test
    fun `第三方视角下只有官方附件的轮次整轮丢弃`() {
        val out = ContextRebuilder.build(
            listOf(
                userWithAttachments(1, "", listOf(HistoryAttachment(remoteId = "file_1", isImage = true))),
                assistant(2, "不该出现"),
            ),
            currentReminder = null,
            flavor = ImageFlavor.OPENAI,
        )
        assertTrue(out.isEmpty())
    }
}
