package io.tinychat.net.account

import io.tinychat.model.DiscoveredModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountModelDiscoveryTest {
    @Test
    fun `DeepSeek reads its published context and output limits`() {
        val models = AccountClient.parseModels(
            """{"object":"list","data":[{"id":"deepseek-v4-pro","owned_by":"deepseek",
                "context_window":1048576,"max_output_tokens":393216}]}""",
        )

        assertEquals(listOf(DiscoveredModel("deepseek-v4-pro", 1_048_576, 393_216)), models)
    }

    @Test
    fun `OpenCode keeps context distinct from the input limit`() {
        val models = AccountClient.parseModels(
            """{"data":[
                {"id":"big-pickle","limit":{"context":200000,"input":160000,"output":32000}},
                {"id":"deepseek-v4.1-flash","limit":{"context":1000000,"output":384000}}
            ]}""",
        )

        assertEquals(listOf(
            DiscoveredModel("big-pickle", 200_000, 32_000),
            DiscoveredModel("deepseek-v4.1-flash", 1_000_000, 384_000),
        ), models)
    }

    @Test
    fun `OpenRouter reads model context and provider completion limit`() {
        val models = AccountClient.parseModels(
            """{"data":[
                {"id":"route","context_length":1048576,
                 "top_provider":{"context_length":262144,"max_completion_tokens":65536}},
                {"id":"provider-only","top_provider":{"context_length":262144,"max_completion_tokens":32768}},
                {"id":"no-output-limit","context_length":4096,"top_provider":{"max_completion_tokens":null}}
            ]}""",
        )

        assertEquals(listOf(
            DiscoveredModel("route", 1_048_576, 65_536),
            DiscoveredModel("provider-only", 262_144, 32_768),
            DiscoveredModel("no-output-limit", 4_096),
        ), models)
    }

    @Test
    fun `Together accepts a top-level model array and vLLM uses max model length`() {
        assertEquals(
            listOf(DiscoveredModel("together-model", 128_000)),
            AccountClient.parseModels("""[{"id":"together-model","context_length":128000}]"""),
        )
        assertEquals(
            listOf(DiscoveredModel("vllm-model", 131_072)),
            AccountClient.parseModels("""{"data":[{"id":"vllm-model","max_model_len":131072}]}"""),
        )
    }

    @Test
    fun `Anthropic and compatible endpoints can supply either limit independently`() {
        val models = AccountClient.parseModels(
            """{"models":[
                {"id":"claude","max_input_tokens":200000,"max_tokens":64000},
                {"id":"context-only","context_window":"128000"},
                {"id":"output-only","max_output_tokens":"32768"},
                {"id":"proxy","model_info":{"max_input_tokens":16000,"max_output_tokens":4000}},
                {"id":"compatible","contextWindow":64000,"maxOutputTokens":24000},
                {"id":"id-only"}
            ]}""",
        )

        assertEquals(listOf(
            DiscoveredModel("claude", 200_000, 64_000),
            DiscoveredModel("context-only", 128_000),
            DiscoveredModel("output-only", maxOutputTokens = 32_768),
            DiscoveredModel("proxy", 16_000, 4_000),
            DiscoveredModel("compatible", 64_000, 24_000),
            DiscoveredModel("id-only"),
        ), models)
    }

    @Test
    fun `malformed limits are ignored without losing entries or duplicate metadata`() {
        val models = AccountClient.parseModels(
            """{"data":[
                {"id":"valid","context_length":-1,"max_output_tokens":8192},
                {"id":"bad-limits","context_length":{},"max_output_tokens":2147483648,"max_tokens":"0"},
                null, {"id":{}}, {"id":" "},
                {"id":"valid","limit":{"context":120000,"output":4096}}
            ]}""",
        )

        assertEquals(listOf(DiscoveredModel("valid", 120_000, 8_192), DiscoveredModel("bad-limits")), models)
    }

    @Test
    fun `missing catalog stays an account error`() {
        assertThrows(AccountException::class.java) { AccountClient.parseModels("""{"data":{}}""") }
    }
}
