package io.tinychat.net.protocol

import io.tinychat.model.KnownProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderModelsTest {
    @Test
    fun `Anthropic model discovery and key check use Messages authentication`() {
        val provider = KnownProviders.catalog.single { it.id == "anthropic" }
        val requests = listOf(
            ProviderModels.keyCheckRequest(provider, "test-key"),
            ProviderModels.listRequest(provider, "test-key"),
        )

        requests.forEach { request ->
            assertEquals("https://api.anthropic.com/v1/models", request.url.toString())
            assertEquals("test-key", request.header("x-api-key"))
            assertEquals("2023-06-01", request.header("anthropic-version"))
            assertNull(request.header("Authorization"))
        }
    }

    @Test
    fun `Z ai models use the versioned coding plan endpoint`() {
        val provider = KnownProviders.catalog.single { it.id == "zai-coding-cn" }
        val request = ProviderModels.listRequest(provider, "test-key")

        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4/models", request.url.toString())
        assertEquals("Bearer test-key", request.header("Authorization"))
    }

    @Test
    fun `OpenRouter key validation uses the key endpoint while discovery uses the catalog`() {
        val provider = KnownProviders.catalog.single { it.id == "openrouter" }
        val check = ProviderModels.keyCheckRequest(provider, "test-key")
        val list = ProviderModels.listRequest(provider, "test-key")

        assertEquals("https://openrouter.ai/api/v1/key", check.url.toString())
        assertEquals("Bearer test-key", check.header("Authorization"))
        assertEquals("https://openrouter.ai/api/v1/models", list.url.toString())
    }
}
