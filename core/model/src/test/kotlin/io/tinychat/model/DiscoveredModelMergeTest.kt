package io.tinychat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiscoveredModelMergeTest {
    @Test
    fun `refresh updates limits independently and preserves the custom directory`() {
        val provider = KnownProviders.genericOpenAiCompatible("custom", "Custom", "https://example.com", "known")
        val saved = provider.models.single().copy(
            displayName = "Saved title",
            contextWindow = 500_000,
            maxOutputTokens = 12_345,
            capabilities = Capabilities(imageInput = true),
        )
        val manual = saved.copy(id = "manual")
        val results = provider.copy(models = listOf(saved, manual)).mergeDiscoveredModels(listOf(
            DiscoveredModel("known", contextWindow = 262_144),
            DiscoveredModel("new", maxOutputTokens = 32_768),
        ))

        assertEquals(saved.copy(contextWindow = 262_144), results[0])
        assertEquals(manual, results[1])
        assertEquals("new", results[2].id)
        assertEquals(0, results[2].contextWindow)
        assertEquals(32_768, results[2].maxOutputTokens)
        assertFalse(results[2].capabilities.thinking)
    }

    @Test
    fun `managed catalog uses provider order and updates known model limits`() {
        val provider = KnownProviders.deepSeek
        val model = provider.models.first()
        val results = provider.mergeDiscoveredModels(listOf(
            DiscoveredModel("new"),
            DiscoveredModel(model.id, maxOutputTokens = 393_216),
        ))

        assertEquals(listOf("new", model.id), results.map { it.id })
        assertEquals(0, results[0].maxOutputTokens)
        assertEquals(model.copy(maxOutputTokens = 393_216), results[1])
    }
}
