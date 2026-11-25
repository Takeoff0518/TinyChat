package io.tinychat.net.account

import io.tinychat.model.DiscoveredModel
import io.tinychat.model.Provider
import io.tinychat.net.protocol.ProviderModels
import io.tinychat.net.transport.OkHttpChatTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** 账户接口（M7-4/M7-6）：`GET /models` 校验 Key，`GET /user/balance` 查余额。 */
object AccountRequests {

    /** 校验 Key 用；不计费，也不消耗额度。 */
    const val MODELS_PATH = "/models"

    /** 余额；只有 DeepSeek 官方有。 */
    const val BALANCE_PATH = "/user/balance"

    fun get(baseUrl: String, path: String, apiKey: String): Request =
        get(baseUrl.trimEnd('/') + path, apiKey)

    internal fun get(url: String, apiKey: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
}

/** Key 校验结论：只有三种，够 UI 用了。 */
sealed interface KeyCheck {
    data object Ok : KeyCheck
    data object Invalid : KeyCheck
    data class Failed(val message: String) : KeyCheck
}

/** 一条余额记录（`balance_infos[]`）。金额是字符串，服务端可能给 "110.00" 或 "0"。 */
data class BalanceInfo(
    val currency: String,
    val totalBalance: String,
    val grantedBalance: String,
    val toppedUpBalance: String,
)

data class BalanceSnapshot(
    val isAvailable: Boolean,
    val infos: List<BalanceInfo>,
) {
    /** 人民币优先（官方以 CNY 结算）；没有就取第一条。 */
    val primary: BalanceInfo?
        get() = infos.firstOrNull { it.currency.equals("CNY", ignoreCase = true) } ?: infos.firstOrNull()
}

class AccountException(message: String, val httpStatus: Int? = null) : Exception(message)

/**
 * 账户客户端。与聊天共用一套 OkHttp 配置，但**不挂日志拦截器**（请求头里有 Key）。
 */
class AccountClient(
    private val client: OkHttpClient = OkHttpChatTransport.defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    /** 校验 Key：200 算通过，401 算无效，其余给一句人话。 */
    suspend fun checkKey(apiKey: String): KeyCheck = checkKey(AccountRequests.get(baseUrl, AccountRequests.MODELS_PATH, apiKey))

    /**
     * 校验某个服务商的 Key（M8-6）：地址与鉴权按服务商协议选择。
     * 只发 GET，不计费。
     */
    suspend fun checkKey(provider: Provider, apiKey: String): KeyCheck =
        checkKey(ProviderModels.keyCheckRequest(provider, apiKey))

    suspend fun checkKey(url: String, apiKey: String): KeyCheck =
        checkKey(AccountRequests.get(url, apiKey))

    private suspend fun checkKey(request: Request): KeyCheck = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> KeyCheck.Ok
                    response.code == 401 || response.code == 403 -> KeyCheck.Invalid
                    response.code == 402 -> KeyCheck.Failed("账户余额不足，充值后即可继续")
                    response.code == 429 -> KeyCheck.Failed("操作太频繁，稍后再试")
                    response.code >= 500 -> KeyCheck.Failed("服务异常，请稍后再试")
                    else -> KeyCheck.Failed("连接失败（${response.code}）")
                }
            }
        } catch (e: IOException) {
            KeyCheck.Failed("无法连接网络，请检查网络设置后重试")
        }
    }

    /**
     * 拉模型目录（`GET …/models`）。保留服务商提供的限制；401 抛 [AccountException] 带 401，
     * 让 UI 能区分「Key 无效」与「该地址没这个接口」。
     */
    suspend fun models(provider: Provider, apiKey: String): List<DiscoveredModel> =
        models(ProviderModels.listRequest(provider, apiKey))

    suspend fun modelIds(provider: Provider, apiKey: String): List<String> =
        models(provider, apiKey).map(DiscoveredModel::id)

    suspend fun modelIds(url: String, apiKey: String): List<String> =
        models(AccountRequests.get(url, apiKey)).map(DiscoveredModel::id)

    private suspend fun models(request: Request): List<DiscoveredModel> = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val message = when (response.code) {
                        401, 403 -> "API Key 无效，请检查后重试"
                        404 -> "未获取到模型列表，可手动添加"
                        else -> "获取模型列表失败（${response.code}）"
                    }
                    throw AccountException(message, response.code)
                }
                parseModels(response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw AccountException("无法连接网络，请检查网络设置后重试", null)
        }
    }

    suspend fun balance(apiKey: String): BalanceSnapshot = withContext(Dispatchers.IO) {
        val request = AccountRequests.get(baseUrl, AccountRequests.BALANCE_PATH, apiKey)
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw AccountException("查询余额失败（${response.code}）", response.code)
                }
                parseBalance(response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw AccountException("无法连接网络，请检查网络设置后重试", null)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        private val json = Json { ignoreUnknownKeys = true }

        /** 容错解析：缺字段按 0，类型不对也尽量给出结构，让 UI 有东西可显示。 */
        fun parseBalance(raw: String): BalanceSnapshot {
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: throw AccountException("余额返回格式无法解析")
            val infos = runCatching {
                root["balance_infos"]?.jsonArray.orEmpty().map { element ->
                    val obj = element.jsonObject
                    BalanceInfo(
                        currency = obj.string("currency") ?: "CNY",
                        totalBalance = obj.string("total_balance") ?: "0",
                        grantedBalance = obj.string("granted_balance") ?: "0",
                        toppedUpBalance = obj.string("topped_up_balance") ?: "0",
                    )
                }
            }.getOrElse { throw AccountException("余额返回格式无法解析") }
            return BalanceSnapshot(
                isAvailable = root["is_available"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
                infos = infos,
            )
        }

        private fun JsonObject.string(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull

        fun parseModelIds(raw: String): List<String> = parseModels(raw).map(DiscoveredModel::id)

        fun parseModels(raw: String): List<DiscoveredModel> {
            val root = runCatching { json.parseToJsonElement(raw) }.getOrNull()
                ?: throw AccountException("模型列表格式无法解析")
            val array = when (root) {
                is JsonArray -> root // Together returns the list at the top level.
                is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray)
                else -> null
            } ?: throw AccountException("当前接口地址未返回可用模型列表")

            val models = linkedMapOf<String, DiscoveredModel>()
            for (element in array) {
                val model = element as? JsonObject ?: continue
                val id = (model["id"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: continue
                val limits = (model["limit"] as? JsonObject) ?: (model["limits"] as? JsonObject)
                val topProvider = model["top_provider"] as? JsonObject
                val modelInfo = model["model_info"] as? JsonObject
                val previous = models[id]

                // OpenCode publishes separate input and context limits. Use input as a fallback when the
                // catalog only provides that value, as Anthropic does.
                val contextWindow = previous?.contextWindow
                    ?: model.tokens("context_length", "context_window", "contextWindow", "max_context_tokens", "max_model_len")
                    ?: limits?.tokens("context")
                    ?: topProvider?.tokens("context_length")
                    ?: modelInfo?.tokens("context_length", "context_window")
                    ?: model.tokens("max_input_tokens", "input_token_limit", "inputTokenLimit")
                    ?: limits?.tokens("input")
                    ?: modelInfo?.tokens("max_input_tokens")
                val maxOutputTokens = previous?.maxOutputTokens
                    ?: model.tokens("max_output_tokens", "max_completion_tokens", "maxOutputTokens", "outputTokenLimit", "output_token_limit")
                    ?: limits?.tokens("output")
                    ?: topProvider?.tokens("max_completion_tokens")
                    ?: modelInfo?.tokens("max_output_tokens", "max_completion_tokens")
                    ?: model.tokens("max_tokens")
                    ?: modelInfo?.tokens("max_tokens")

                models[id] = DiscoveredModel(id, contextWindow, maxOutputTokens)
            }
            return models.values.toList()
        }

        private fun JsonObject.tokens(vararg fields: String): Int? = fields.firstNotNullOfOrNull { field ->
            (get(field) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        }
    }
}
