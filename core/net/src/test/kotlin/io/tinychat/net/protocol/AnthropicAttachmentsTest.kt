package io.tinychat.net.protocol

import io.tinychat.model.Capabilities
import io.tinychat.net.context.ContextRebuilder
import io.tinychat.net.context.HistoryAttachment
import io.tinychat.net.context.ImageFlavor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnthropicAttachmentsTest {
    @Test
    fun `图片和文档按 Messages 格式发送，用户元信息保持合法角色`() {
        val attachments = listOf(
            HistoryAttachment(remoteId = null, isImage = true, dataUri = "data:image/jpeg;base64,AAAA"),
            HistoryAttachment(
                remoteId = null,
                isImage = false,
                fileName = "report.pdf",
                dataUri = "data:application/pdf;base64,BBBB",
            ),
            HistoryAttachment(remoteId = null, isImage = false, fileName = "notes.md", text = "复查数据"),
            HistoryAttachment(remoteId = "upstream-file-id", isImage = false),
        )
        val messages = listOf(
            WireMessage.text(WireMessage.ROLE_REMINDER, "今天是星期一"),
            ContextRebuilder.userMessage("请总结", attachments, ImageFlavor.ANTHROPIC),
        )
        val request = AnthropicMessagesProtocol.build(
            baseUrl = "https://gateway.example/v1",
            apiKey = "test-key",
            command = ChatCommand(
                capabilities = Capabilities(imageInput = true, fileUpload = true),
                model = "test-model",
                messages = messages,
            ),
        )
        val body = Json.parseToJsonElement(request.bodyJson).jsonObject
        val message = body.getValue("messages").jsonArray.single().jsonObject
        val content = message.getValue("content").jsonArray.map { it.jsonObject }
        assertEquals("user", message.getValue("role").jsonPrimitive.content)
        assertEquals(listOf("image", "document", "text", "text"), content.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(listOf("base64", "base64"), content.take(2).map {
            it.getValue("source").jsonObject.getValue("type").jsonPrimitive.content
        })
        assertEquals(listOf("image/jpeg", "application/pdf"), content.take(2).map {
            it.getValue("source").jsonObject.getValue("media_type").jsonPrimitive.content
        })
        assertEquals(listOf("AAAA", "BBBB"), content.take(2).map {
            it.getValue("source").jsonObject.getValue("data").jsonPrimitive.content
        })
        assertEquals("report.pdf", content[1].getValue("title").jsonPrimitive.content)
        assertEquals("[附件 notes.md]\n复查数据\n[/附件]\n\n", content[2].getValue("text").jsonPrimitive.content)
        assertEquals("今天是星期一\n\n请总结", content[3].getValue("text").jsonPrimitive.content)
        assertNull(request.headers["anthropic-beta"])
    }
}
