package io.tinychat.data.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.tinychat.data.attachment.AttachmentStore
import io.tinychat.data.attachment.AttachmentUploader
import io.tinychat.data.db.DatabaseFactory
import io.tinychat.data.provider.ProviderStore
import io.tinychat.model.FragmentPayloadCodec
import io.tinychat.model.KnownProviders
import io.tinychat.model.MessageStatus
import io.tinychat.model.SearchResult
import io.tinychat.model.ToolSearchPayload
import io.tinychat.model.WebSearchProvider
import io.tinychat.net.SseEvent
import io.tinychat.net.protocol.PreparedRequest
import io.tinychat.net.transport.ChatTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRepositoryWebSearchTest {
    @Test
    fun `cancel during pre-stream search leaves the new answer visible and incomplete`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = DatabaseFactory.createInMemory(context)
        try {
            val providers = ProviderStore.create(db)
            providers.save(KnownProviders.genericOpenAiCompatible("test", "Test", "https://example.com/v1", "chat"))
            val searching = CompletableDeferred<Unit>()
            val repo = ChatRepository(
                db = db,
                transport = object : ChatTransport {
                    override fun stream(request: PreparedRequest): Flow<SseEvent> = emptyFlow()
                },
                attachmentStore = AttachmentStore(context),
                uploader = AttachmentUploader(),
                providerStore = providers,
                apiKeyFor = { "chat-key" },
                webSearchProvider = { WebSearchProvider.TAVILY },
                webSearch = { _, _ -> searching.complete(Unit); awaitCancellation() },
            )
            val id = repo.createSession(providerId = "test", model = "chat", searchEnabled = true)
            val work = launch { repo.send(id, "pending question") }
            withTimeout(5_000) { searching.await() }
            val answer = db.sessionDao().byId(id)!!.currentMessageId!!
            assertEquals(MessageStatus.WIP, db.messageDao().byId(answer)?.status)

            work.cancelAndJoin()
            assertEquals(answer, db.sessionDao().byId(id)?.currentMessageId)
            assertEquals(MessageStatus.INCOMPLETE, db.messageDao().byId(answer)?.status)
            assertEquals(2, db.messageDao().countInSession(id))
        } finally {
            db.close()
        }
    }

    @Test
    fun `the selected search service is independent of the chat provider`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = DatabaseFactory.createInMemory(context)
        try {
            val providers = ProviderStore.create(db)
            providers.ensureSeeded()
            val custom = KnownProviders.genericOpenAiCompatible("test", "Test", "https://example.com/v1", "chat")
            providers.save(custom)
            val requests = mutableListOf<PreparedRequest>()
            val searchQueries = mutableListOf<Pair<WebSearchProvider, String>>()
            var searchProvider = WebSearchProvider.DEEPSEEK
            val result = SearchResult(url = "https://example.com/article", title = "Search hit", snippet = "Fresh info")
            val repo = ChatRepository(
                db = db,
                transport = object : ChatTransport {
                    override fun stream(request: PreparedRequest): Flow<SseEvent> = flow { requests += request }
                },
                attachmentStore = AttachmentStore(context),
                uploader = AttachmentUploader(),
                providerStore = providers,
                apiKeyFor = { "chat-key" },
                webSearchProvider = { searchProvider },
                webSearch = { selected, query ->
                    searchQueries += selected to query
                    listOf(result)
                },
            )

            for (selected in WebSearchProvider.entries) {
                searchProvider = selected
                for (provider in listOf(custom, KnownProviders.deepSeek)) {
                    val id = repo.createSession(providerId = provider.id, model = provider.models.first().id, searchEnabled = true)
                    val outcome = repo.send(id, "最新消息")
                    val rows = db.fragmentDao().byMessage(outcome.messageId)
                    val request = requests.last().bodyJson
                    if (selected == WebSearchProvider.DEEPSEEK && provider == KnownProviders.deepSeek) {
                        assertTrue(request.contains("deepseek_web_search"))
                        assertTrue(rows.isEmpty())
                    } else {
                        val search = FragmentPayloadCodec.decode(rows.single().payload) as ToolSearchPayload
                        assertTrue(request.contains(result.url))
                        assertFalse(request.contains("deepseek_web_search"))
                        assertEquals(listOf(result), search.results)
                        assertEquals(selected to "最新消息", searchQueries.last())
                    }
                }
            }

            val off = repo.createSession(providerId = custom.id, model = custom.models.first().id)
            repo.send(off, "离线回答")
            assertFalse(requests.last().bodyJson.contains(result.url))
            assertEquals(5, searchQueries.size)
        } finally {
            db.close()
        }
    }
}
