package io.tinychat.feature.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tinychat.designsystem.TcIcons
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcTypography
import io.tinychat.model.DiscoveredModel
import io.tinychat.model.KnownProviders
import io.tinychat.model.ModelInfo
import io.tinychat.model.Provider
import io.tinychat.model.ProviderCheck
import io.tinychat.model.ProviderKind
import io.tinychat.model.ProviderNaming
import io.tinychat.model.ProviderProtocol
import io.tinychat.model.ThinkingMode
import io.tinychat.model.mergeDiscoveredModels
import io.tinychat.ui.TcAlertDialog
import io.tinychat.ui.TcButton
import io.tinychat.ui.TcIconButton
import io.tinychat.ui.TcTopBar
import kotlinx.coroutines.launch

/**
 * 服务商与 API Key（M8-6，docs/02 §12.6 重写版）。
 *
 * 三个原则：
 * 1. 一屏只有一个主按钮（保存），校验是**状态**不是动作；
 * 2. 删除密钥/删除服务商分开：前者只删本机凭据，后者才影响会话；
 * 3. 「清除」只在真的存了密钥时出现，并且写清对象。
 */
@Composable
fun ProviderListScreen(
    providers: List<Provider>,
    keyedProviders: Set<String>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Provider) -> Unit,
    onDelete: (Provider) -> Unit,
) {
    val palette = TcTheme.palette
    var pendingDelete by remember { mutableStateOf<Provider?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg),
    ) {
        TcTopBar(
            title = stringResource(R.string.prefs_detail_providers),
            leading = { TcIconButton(TcIcons.Back, stringResource(R.string.prefs_detail_back), onBack) },
        )

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            providers.forEach { provider ->
                ProviderCard(
                    provider = provider,
                    hasKey = provider.id in keyedProviders,
                    onEdit = { onEdit(provider) },
                    onDelete = { pendingDelete = provider },
                )
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, palette.borderL2, RoundedCornerShape(12.dp))
                    .clickable(onClick = onAdd)
                    .padding(vertical = 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "＋ ${stringResource(R.string.prefs_detail_provider_add)}",
                    style = TcTypography.bodyMedium,
                    color = palette.labelSecondary,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingDelete?.let { provider ->
        TcAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.prefs_detail_provider_delete, provider.localizedName())) },
            text = { Text(stringResource(R.string.prefs_detail_provider_delete_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(provider)
                }) { Text(stringResource(R.string.prefs_detail_delete), color = palette.danger) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.prefs_detail_cancel), color = TcTheme.palette.labelSecondary) } },
        )
    }
}

@Composable
private fun ProviderCard(
    provider: Provider,
    hasKey: Boolean,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val palette = TcTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .border(1.dp, palette.borderL2, RoundedCornerShape(12.dp))
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = provider.localizedName(),
            style = TcTypography.bodyLarge,
            color = palette.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        StatusDot(provider = provider, hasKey = hasKey)
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.prefs_detail_edit),
            style = TcTypography.bodyMedium,
            color = palette.brand,
            modifier = Modifier
                .clickable(onClick = onEdit)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
        if (onDelete != null) {
            Text(
                text = stringResource(R.string.prefs_detail_delete),
                style = TcTypography.bodyMedium,
                color = palette.danger,
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun StatusDot(provider: Provider, hasKey: Boolean) {
    val palette = TcTheme.palette
    val color = when {
        !hasKey -> palette.labelTertiary
        provider.check == ProviderCheck.OK -> palette.brand
        provider.check == ProviderCheck.FAILED -> palette.danger
        else -> palette.labelTertiary
    }
    Box(
        Modifier
            .size(8.dp)
            .background(color, RoundedCornerShape(4.dp)),
    )
}

/**
 * 添加 / 编辑同一个页面。
 *
 * `provider == null` 是新增（两个 tab）；否则是编辑（直接表单，不可改 ID）。
 */
/** 离开保护用的一份表单快照（02 §10.2）。只比会落库的东西，状态文案不参与。 */
private data class FormSnapshot(
    val id: String,
    val name: String,
    val baseUrl: String,
    val protocol: ProviderProtocol,
    /** 整条模型信息都进快照：改了上下文窗口或思考开关也算未保存改动 */
    val models: List<ModelInfo>,
)
@Composable
fun ProviderEditorScreen(
    provider: Provider?,
    existing: List<Provider>,
    hasKey: Boolean,
    onBack: () -> Unit,
    onSave: suspend (Provider, String?) -> Unit,
    onCheck: suspend (Provider, String?) -> String?,
    onClearKey: suspend (String) -> Unit,
    onFetchModels: suspend (Provider, String?) -> List<DiscoveredModel>,
) {
    val palette = TcTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adding = provider == null
    // 目录里的预设可以重复添加（决策 48）：两块不同的 Key、两个网关、两个地区互不影响
    val catalogEntries = KnownProviders.catalog
    val existingIds = remember(existing) { existing.map { it.id }.toSet() }
    val existingNames = remember(existing) { existing.map { it.displayName }.toSet() }

    var tab by remember { mutableStateOf(EditorTab.CATALOG) }
    var pickedCatalog by remember { mutableStateOf<Provider?>(null) }
    var choosingCatalog by remember { mutableStateOf(true) }
    val formScroll = rememberScrollState()
    var id by remember { mutableStateOf(provider?.id.orEmpty()) }
    var name by remember { mutableStateOf(provider?.displayName.orEmpty()) }
    var baseUrl by remember { mutableStateOf(provider?.baseUrl.orEmpty()) }
    var protocol by remember { mutableStateOf(provider?.protocol ?: ProviderProtocol.OPENAI_CHAT) }
    var models by remember { mutableStateOf(provider?.models.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var checkStatus by remember { mutableStateOf(provider?.check ?: ProviderCheck.UNKNOWN) }
    var checkMessage by remember { mutableStateOf(provider?.checkMessage) }
    var checking by remember { mutableStateOf(false) }
    var keyCleared by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    // 离开保护（02 §10.2）：打开这一页时记一份快照，任何一项与之不同就要问一句。
    // 取消按钮已经删掉，返回是唯一的出口，没有这道确认就会静默丢掉填了一半的密钥。
    val initialForm = remember {
        FormSnapshot(
            id = id,
            name = name,
            baseUrl = baseUrl,
            protocol = protocol,
            models = models,
        )
    }
    val dirty = FormSnapshot(
        id = id,
        name = name,
        baseUrl = baseUrl,
        protocol = protocol,
        models = models,
    ) != initialForm || key.isNotBlank()
    var confirmDiscard by remember { mutableStateOf(false) }

    fun leave() {
        if (busy) return
        if (dirty) confirmDiscard = true else onBack()
    }

    BackHandler(enabled = dirty && !busy) { confirmDiscard = true }

    // 预设解锁（决策 48）：任何服务商都能改地址、协议、模型，也能删；预设只负责预填
    val catalogMode = adding && tab == EditorTab.CATALOG
    val effective = if (catalogMode) pickedCatalog else provider
    val kindOf = effective?.kind ?: ProviderKind.OPENAI_COMPATIBLE
    // 模型始终以这一份可变状态为准（选目录服务商时已把它的模型灌进来）；
    // 第一版目录模式读的是 pickedCatalog.models，导致「拉取成功但界面不更新」
    val currentModels = models
    val canSave = when {
        busy -> false
        catalogMode -> !choosingCatalog && pickedCatalog != null && name.isNotBlank() && baseUrl.isNotBlank() &&
            currentModels.isNotEmpty()
        else -> name.isNotBlank() && baseUrl.isNotBlank() && currentModels.isNotEmpty() &&
            (provider != null || id.matches(ID_REGEX))
    }

    fun draftProvider(): Provider {
        val base = effective
        return (base ?: Provider(
            id = id,
            kind = ProviderKind.OPENAI_COMPATIBLE,
            displayName = name,
            baseUrl = baseUrl,
            models = models,
            protocol = protocol,
        )).copy(
            id = if (adding) id else provider!!.id,
            displayName = name,
            baseUrl = baseUrl,
            protocol = protocol,
            // 显示名留空 = 同 ID（DSH 的口径），省得列表里出现空行
            models = currentModels.map { it.copy(displayName = it.displayName.ifBlank { it.id }) },
            check = provider?.check ?: ProviderCheck.UNKNOWN,
            checkMessage = provider?.checkMessage,
        )
    }

    fun save() {
        val candidate = draftProvider()
        val localError = when {
            candidate.displayName.isBlank() -> context.getString(R.string.prefs_detail_provider_name_required)
            candidate.baseUrl.isBlank() -> context.getString(R.string.prefs_detail_provider_url_required)
            !candidate.baseUrl.startsWith("https://") -> context.getString(R.string.prefs_detail_provider_https_required)
            candidate.models.isEmpty() -> context.getString(R.string.prefs_detail_provider_model_required)
            !adding -> null
            existing.any { it.id == candidate.id } -> context.getString(R.string.prefs_detail_provider_id_taken)
            !candidate.id.matches(ID_REGEX) -> context.getString(R.string.prefs_detail_provider_id_invalid)
            else -> null
        }
        if (localError != null) {
            error = localError
            return
        }
        scope.launch {
            busy = true
            error = null
            runCatching {
                onSave(candidate, key.trim().takeIf { it.isNotEmpty() })
                onBack()
            }.onFailure { error = it.message ?: context.getString(R.string.prefs_detail_save_failed) }
            busy = false
        }
    }

    fun recheck() {
        val candidate = draftProvider()
        scope.launch {
            busy = true
            checking = true
            val message = runCatching { onCheck(candidate, key.trim().takeIf { it.isNotEmpty() }) }
                .getOrElse { it.message ?: context.getString(R.string.prefs_detail_provider_check_failed) }
            checkMessage = message
            checkStatus = if (message == null) ProviderCheck.OK else ProviderCheck.FAILED
            checking = false
            keyCleared = false
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg),
    ) {
        TcTopBar(
            title = provider?.localizedName() ?: stringResource(R.string.prefs_detail_provider_add),
            leading = { TcIconButton(TcIcons.Back, stringResource(R.string.prefs_detail_back), ::leave) },
        )

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(formScroll)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            if (adding) {
                Spacer(Modifier.height(4.dp))
                EditorTabs(
                    tab = tab,
                    enabled = !busy,
                    onSelect = {
                        if (tab != it) {
                            key = ""
                            reveal = false
                        }
                        tab = it
                    },
                )
            }

            Spacer(Modifier.height(14.dp))

            if (catalogMode) {
                val picked = pickedCatalog
                if (choosingCatalog || picked == null) {
                    catalogEntries.forEach { item ->
                        CatalogRow(
                            provider = item,
                            picked = picked?.id == item.id,
                            onClick = {
                                if (picked?.id != item.id) {
                                    pickedCatalog = item
                                    key = ""
                                    reveal = false
                                    error = null
                                    id = ProviderNaming.uniqueId(item.id, existingIds)
                                    name = ProviderNaming.uniqueName(item.displayName, existingNames)
                                    baseUrl = item.baseUrl
                                    protocol = item.protocol
                                    models = item.models
                                }
                                choosingCatalog = false
                                scope.launch { formScroll.scrollTo(0) }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                } else {
                    CatalogRow(
                        provider = picked,
                        picked = true,
                        collapsed = true,
                        enabled = !busy,
                        onClick = { choosingCatalog = true },
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (!catalogMode || !choosingCatalog) {
                if (adding) {
                    EditorField(
                        label = stringResource(R.string.prefs_detail_provider_id),
                        value = id,
                        enabled = !busy,
                        placeholder = "acme-gateway",
                        onValueChange = { id = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '-' }.take(32) },
                    )
                    Spacer(Modifier.height(16.dp))
                }

                EditorField(
                    label = stringResource(R.string.prefs_detail_provider_display_name),
                    value = name,
                    enabled = !busy,
                    placeholder = stringResource(R.string.prefs_detail_provider_gateway),
                    onValueChange = { name = it.take(24) },
                )
                Spacer(Modifier.height(16.dp))

                EditorField(
                    label = stringResource(R.string.prefs_detail_provider_url),
                    value = baseUrl,
                    enabled = !busy,
                    placeholder = "https://gateway.example/v1",
                    onValueChange = { baseUrl = it.trim() },
                )
                Spacer(Modifier.height(16.dp))

                ProtocolPicker(
                    picked = protocol,
                    options = protocolOptions(kindOf),
                    enabled = !busy,
                    onSelect = { protocol = it },
                )
                Spacer(Modifier.height(16.dp))

                EditorField(
                    label = stringResource(R.string.prefs_detail_provider_key),
                    value = key,
                    enabled = !busy,
                    placeholder = if (hasKey) stringResource(R.string.prefs_detail_saved) else "",
                    secret = !reveal,
                    onValueChange = { key = it.trim() },
                    trailing = {
                        Text(
                            text = if (reveal) stringResource(R.string.prefs_detail_hide) else stringResource(R.string.prefs_detail_show),
                            style = TcTypography.bodyMedium,
                            color = palette.labelSecondary,
                            modifier = Modifier
                                .clickable { reveal = !reveal }
                                .padding(start = 10.dp),
                        )
                    },
                )

                if (provider != null) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when {
                                checking -> stringResource(R.string.prefs_detail_provider_checking)
                                keyCleared -> stringResource(R.string.prefs_detail_provider_key_cleared)
                                checkStatus == ProviderCheck.OK -> stringResource(R.string.prefs_detail_provider_available)
                                checkStatus == ProviderCheck.FAILED -> checkMessage?.let(context::providerError)
                                    ?: stringResource(R.string.prefs_detail_provider_check_failed)
                                else -> stringResource(R.string.prefs_detail_provider_unchecked)
                            },
                            style = TcTypography.labelMedium,
                            color = when {
                                checking -> palette.labelSecondary
                                keyCleared -> palette.labelTertiary
                                checkStatus == ProviderCheck.OK -> palette.brand
                                checkStatus == ProviderCheck.FAILED -> palette.danger
                                else -> palette.labelTertiary
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (hasKey) {
                            Text(
                                text = stringResource(R.string.prefs_detail_provider_recheck),
                                style = TcTypography.labelMedium,
                                color = palette.brand,
                                modifier = Modifier
                                    .clickable(enabled = !busy) { recheck() }
                                    .padding(6.dp),
                            )
                        }
                    }
                    if (hasKey) {
                        Text(
                            text = stringResource(R.string.prefs_detail_provider_clear_key),
                            style = TcTypography.labelMedium,
                            color = palette.danger,
                            modifier = Modifier
                                .clickable { confirmClear = true }
                                .padding(vertical = 6.dp),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                ModelDirectory(
                    models = currentModels,
                    editable = !busy,
                    canFetch = !busy,
                    busy = busy,
                    fetching = fetching,
                    protocol = protocol,
                    onAdd = { added -> models = models + added },
                    onChange = { index, updated -> models = models.toMutableList().also { it[index] = updated } },
                    onRemove = { index -> models = models.toMutableList().also { it.removeAt(index) } },
                    onFetch = {
                        val candidate = draftProvider()
                        scope.launch {
                            busy = true
                            fetching = true
                            error = null
                            runCatching { onFetchModels(candidate, key.trim().takeIf { it.isNotEmpty() }) }
                                .onSuccess { discovered ->
                                    if (discovered.isEmpty()) {
                                        error = context.getString(R.string.prefs_detail_provider_no_models)
                                    } else {
                                        models = candidate.mergeDiscoveredModels(discovered)
                                    }
                                }
                                .onFailure {
                                    error = it.message ?: context.getString(R.string.prefs_detail_provider_fetch_failed)
                                }
                            fetching = false
                            busy = false
                        }
                    },
                )

                error?.let {
                    Text(
                        text = context.providerError(it),
                        style = TcTypography.labelMedium,
                        color = palette.danger,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        // 动作区（02 §10.2）：一屏一个主操作，取消由顶栏返回承担
        TcButton(
            text = if (busy) stringResource(R.string.prefs_detail_saving) else stringResource(R.string.prefs_detail_save),
            onClick = { save() },
            enabled = canSave,
            fillWidth = true,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp),
        )
    }

    if (confirmDiscard) {
        TcAlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.prefs_detail_discard_changes)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text(stringResource(R.string.prefs_detail_discard), color = TcTheme.palette.brand) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.prefs_detail_keep_editing), color = TcTheme.palette.labelSecondary)
                }
            },
        )
    }
    if (confirmClear) {
        TcAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.prefs_detail_provider_clear_key_confirm)) },
            text = { Text(stringResource(R.string.prefs_detail_provider_clear_key_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        onClearKey(provider!!.id)
                        keyCleared = true
                    }
                }) { Text(stringResource(R.string.prefs_detail_clear), color = palette.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.prefs_detail_cancel), color = TcTheme.palette.labelSecondary) } },
        )
    }
}

private enum class EditorTab { CATALOG, CUSTOM }

@Composable
private fun EditorTabs(tab: EditorTab, enabled: Boolean, onSelect: (EditorTab) -> Unit) {
    val palette = TcTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .background(palette.selector, RoundedCornerShape(10.dp))
            .padding(3.dp),
    ) {
        val tabs = listOf(
            EditorTab.CATALOG to R.string.prefs_detail_provider_catalog,
            EditorTab.CUSTOM to R.string.prefs_detail_provider_custom,
        )
        tabs.forEach { (item, title) ->
            val picked = item == tab
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .background(
                        if (picked) palette.bgLayer2 else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(enabled = enabled) { onSelect(item) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(title),
                    fontSize = 13.sp,
                    color = if (picked) palette.labelPrimary else palette.labelSecondary,
                )
            }
        }
    }
}

@Composable
private fun CatalogRow(
    provider: Provider,
    picked: Boolean,
    collapsed: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = TcTheme.palette
    val description = if (collapsed) stringResource(R.string.prefs_detail_provider_choose_another) else null
    Row(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (picked) palette.brand else palette.borderL2, RoundedCornerShape(10.dp))
            .semantics { selected = picked }
            .clickable(onClickLabel = description, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(provider.localizedName(), style = TcTypography.bodyLarge, color = palette.labelPrimary)
            Text(
                text = Uri.parse(provider.baseUrl).host ?: provider.baseUrl,
                style = TcTypography.labelMedium,
                color = palette.labelTertiary,
                maxLines = 2,
            )
        }
        if (collapsed) {
            Icon(TcIcons.ChevronDown, contentDescription = null, tint = palette.labelSecondary)
        }
    }
}

@Composable
private fun Provider.localizedName(): String = when {
    kind == ProviderKind.DEEPSEEK_OFFICIAL && displayName == KnownProviders.deepSeek.displayName ->
        stringResource(R.string.prefs_detail_provider_official)
    displayName == "Kimi (CN)" -> stringResource(R.string.prefs_detail_provider_kimi_cn)
    displayName == "Kimi (Global)" -> stringResource(R.string.prefs_detail_provider_kimi_global)
    displayName == "MiniMax (CN)" -> stringResource(R.string.prefs_detail_provider_minimax_cn)
    displayName == "MiniMax (Global)" -> stringResource(R.string.prefs_detail_provider_minimax_global)
    displayName == "GLM Coding Plan (CN)" -> stringResource(R.string.prefs_detail_provider_glm_cn)
    displayName == "Qwen Token Plan (CN)" -> stringResource(R.string.prefs_detail_provider_qwen_cn)
    else -> displayName
}

@Composable
private fun ModelDirectory(
    models: List<ModelInfo>,
    editable: Boolean,
    canFetch: Boolean,
    busy: Boolean,
    fetching: Boolean,
    protocol: ProviderProtocol,
    onAdd: (ModelInfo) -> Unit,
    onChange: (Int, ModelInfo) -> Unit,
    onRemove: (Int) -> Unit,
    onFetch: () -> Unit,
) {
    val palette = TcTheme.palette
    // 模型详情弹层（决策 49）：index < 0 表示新增
    var editing by remember { mutableStateOf<Pair<Int, ModelInfo>?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.prefs_detail_provider_models), style = TcTypography.labelMedium, color = palette.labelSecondary, modifier = Modifier.weight(1f))
        if (canFetch) {
            Text(
                text = if (fetching) stringResource(R.string.prefs_detail_provider_fetching) else stringResource(R.string.prefs_detail_provider_fetch),
                style = TcTypography.labelMedium,
                color = if (busy) palette.labelTertiary else palette.brand,
                modifier = Modifier
                    .clickable(enabled = !busy, onClick = onFetch)
                    .padding(6.dp),
            )
        }
    }
    Spacer(Modifier.height(6.dp))

    if (models.isNotEmpty()) {
        // 表头：两列各有列名，右边留出删除位，扫一眼就知道哪列是 ID（决策 49）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, end = 2.dp, bottom = 4.dp),
        ) {
            Text(stringResource(R.string.prefs_detail_provider_model), style = TcTypography.labelMedium, color = palette.labelTertiary, modifier = Modifier.weight(1.2f))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.prefs_detail_name), style = TcTypography.labelMedium, color = palette.labelTertiary, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(28.dp))
        }
    }

    models.forEachIndexed { index, model ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = editable && !busy) { editing = index to model }
                .padding(horizontal = 2.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (model.id.isBlank()) stringResource(R.string.prefs_detail_provider_model_unnamed) else model.id,
                    style = TcTypography.bodyMedium,
                    color = if (model.id.isBlank()) palette.danger else palette.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.2f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = model.displayName.ifBlank { model.id }.ifBlank { "—" },
                    style = TcTypography.bodyMedium,
                    color = palette.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (editable) {
                    Text(
                        text = "✕",
                        fontSize = 13.sp,
                        color = palette.labelTertiary,
                        modifier = Modifier
                            .clickable(
                                enabled = !busy,
                                onClickLabel = stringResource(R.string.prefs_detail_delete),
                            ) { onRemove(index) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Text(
                text = modelSummary(model),
                style = TcTypography.labelMedium,
                color = palette.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }

    if (editable) {
        Text(
            text = "＋ ${stringResource(R.string.prefs_detail_provider_model_add)}",
            style = TcTypography.labelMedium,
            color = palette.labelSecondary,
            modifier = Modifier
                .border(1.dp, palette.borderL2, RoundedCornerShape(8.dp))
                .clickable(enabled = !busy) {
                    editing = -1 to ModelInfo(
                        id = "",
                        displayName = "",
                        contextWindow = 128_000,
                        maxOutputTokens = 8_192,
                        capabilities = io.tinychat.model.Capabilities(thinking = false),
                    )
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }

    editing?.let { (index, model) ->
        ModelDetailDialog(
            model = model,
            isNew = index < 0,
            protocol = protocol,
            onDismiss = { editing = null },
            onConfirm = { updated ->
                editing = null
                if (index < 0) onAdd(updated) else onChange(index, updated)
            },
        )
    }
}

/** 模型行下面那行摘要：能力边界在列表里就能读到，不用点进去（决策 49）。 */
@Composable
private fun modelSummary(model: ModelInfo): String = listOfNotNull(
    if (model.contextWindow > 0) {
        stringResource(R.string.prefs_detail_provider_features_context, shortTokens(model.contextWindow))
    } else {
        stringResource(R.string.prefs_detail_provider_features_context_unknown)
    },
    if (model.maxOutputTokens > 0) {
        stringResource(R.string.prefs_detail_provider_features_output, shortTokens(model.maxOutputTokens))
    } else {
        stringResource(R.string.prefs_detail_provider_features_output_unknown)
    },
    when {
        model.capabilities.effort -> stringResource(R.string.prefs_detail_provider_features_effort)
        model.capabilities.thinking -> stringResource(R.string.prefs_detail_provider_features_reasoning)
        else -> null
    },
    if (model.capabilities.imageInput) stringResource(R.string.prefs_detail_provider_features_vision) else null,
    if (model.capabilities.fileUpload) stringResource(R.string.prefs_detail_provider_features_files) else null,
).joinToString(" · ")

private fun shortTokens(value: Int): String = when {
    value % 1_000_000 == 0 -> "${value / 1_000_000}M"
    value % 1_000 == 0 -> "${value / 1_000}k"
    else -> value.toString()
}

/**
 * 模型详情（决策 49）。
 *
 * 上下文窗口与最大输出不是摆设：前者是输入区圆环的分母，后者直接进 Anthropic 的 `max_tokens`。
 * 拉取模型时给的 128k / 8192 只是占位，必须让用户能改。
 */
@Composable
private fun ModelDetailDialog(
    model: ModelInfo,
    isNew: Boolean,
    protocol: ProviderProtocol,
    onDismiss: () -> Unit,
    onConfirm: (ModelInfo) -> Unit,
) {
    val palette = TcTheme.palette
    var id by remember { mutableStateOf(model.id) }
    var name by remember { mutableStateOf(model.displayName) }
    var contextText by remember { mutableStateOf(model.contextWindow.takeIf { it > 0 }?.toString().orEmpty()) }
    var outputText by remember { mutableStateOf(model.maxOutputTokens.takeIf { it > 0 }?.toString().orEmpty()) }
    var thinking by remember { mutableStateOf(model.capabilities.thinking) }
    var effort by remember { mutableStateOf(model.capabilities.effort) }
    var vision by remember { mutableStateOf(model.capabilities.imageInput) }
    var pdf by remember { mutableStateOf(model.capabilities.fileUpload) }
    val compatibleProtocol = protocol != ProviderProtocol.DEEPSEEK_MESSAGES

    TcAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.bg,
        title = {
            Text(
                text = if (isNew) stringResource(R.string.prefs_detail_provider_model_add) else stringResource(R.string.prefs_detail_provider_model_edit),
                style = TcTypography.titleMedium,
                color = palette.labelPrimary,
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                EditorField(
                    label = stringResource(R.string.prefs_detail_provider_model_id),
                    value = id,
                    enabled = true,
                    placeholder = "gpt-4.1",
                    onValueChange = { id = it.trim() },
                )
                Spacer(Modifier.height(14.dp))
                EditorField(
                    label = stringResource(R.string.prefs_detail_provider_display_name),
                    value = name,
                    enabled = true,
                    placeholder = id,
                    onValueChange = { name = it.take(40) },
                )
                Spacer(Modifier.height(14.dp))
                EditorField(
                    label = stringResource(R.string.prefs_detail_provider_context),
                    value = contextText,
                    enabled = true,
                    placeholder = "128000",
                    onValueChange = { contextText = it.filter(Char::isDigit).take(9) },
                )
                Spacer(Modifier.height(14.dp))
                EditorField(
                    label = stringResource(R.string.prefs_detail_provider_output),
                    value = outputText,
                    enabled = true,
                    placeholder = "8192",
                    onValueChange = { outputText = it.filter(Char::isDigit).take(9) },
                )
                Spacer(Modifier.height(6.dp))
                DialogSwitch(
                    title = stringResource(R.string.prefs_detail_provider_reasoning),
                    checked = thinking,
                    enabled = true,
                    onCheckedChange = {
                        thinking = it
                        if (!it) effort = false
                    },
                )
                DialogSwitch(
                    title = stringResource(R.string.prefs_detail_provider_features_effort),
                    checked = effort && thinking,
                    enabled = thinking,
                    onCheckedChange = { effort = it },
                )
                if (compatibleProtocol) {
                    DialogSwitch(
                        title = stringResource(R.string.prefs_detail_provider_features_vision),
                        checked = vision,
                        enabled = true,
                        onCheckedChange = { vision = it },
                    )
                    DialogSwitch(
                        title = stringResource(R.string.prefs_detail_provider_native_pdf),
                        checked = pdf,
                        enabled = true,
                        onCheckedChange = { pdf = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = id.isNotBlank(),
                onClick = {
                    onConfirm(
                        model.copy(
                            id = id,
                            displayName = name.trim(),
                            contextWindow = contextText.toIntOrNull() ?: model.contextWindow,
                            maxOutputTokens = outputText.toIntOrNull() ?: model.maxOutputTokens,
                            thinking = when {
                                !thinking -> ThinkingMode.NONE
                                protocol == ProviderProtocol.ANTHROPIC_MESSAGES -> ThinkingMode.BUDGET
                                else -> ThinkingMode.EFFORT
                            },
                            capabilities = model.capabilities.copy(
                                thinking = thinking,
                                effort = effort && thinking,
                                imageInput = if (compatibleProtocol) vision else model.capabilities.imageInput,
                                fileUpload = if (compatibleProtocol) pdf else model.capabilities.fileUpload,
                            ),
                        ),
                    )
                },
            ) {
                Text(stringResource(R.string.prefs_detail_save), color = if (id.isNotBlank()) palette.brand else palette.labelTertiary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.prefs_detail_cancel), color = palette.labelSecondary) }
        },
    )
}

@Composable
private fun DialogSwitch(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = TcTheme.palette
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = TcTypography.bodyMedium,
            color = if (enabled) palette.labelPrimary else palette.labelTertiary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = palette.brand,
                checkedThumbColor = palette.brandOn,
            ),
        )
    }
}

@Composable
private fun ProtocolPicker(
    picked: ProviderProtocol,
    options: List<ProviderProtocol>,
    enabled: Boolean,
    onSelect: (ProviderProtocol) -> Unit,
) {
    val palette = TcTheme.palette
    var open by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.prefs_detail_provider_protocol), style = TcTypography.labelMedium, color = palette.labelSecondary)
        Spacer(Modifier.height(6.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .border(1.dp, palette.borderL2, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) { open = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = picked.title(),
                    style = TcTypography.bodyMedium,
                    color = if (enabled) palette.labelPrimary else palette.labelTertiary,
                    modifier = Modifier.weight(1f),
                )
                if (enabled) Text("▾", color = palette.labelTertiary, fontSize = 12.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.title(), color = palette.labelPrimary) },
                        onClick = {
                            open = false
                            onSelect(item)
                        },
                    )
                }
            }
        }
    }
}

private fun ProviderProtocol.title(): String = when (this) {
    ProviderProtocol.OPENAI_CHAT -> "OpenAI Chat Completions"
    ProviderProtocol.ANTHROPIC_MESSAGES -> "Anthropic Messages"
    ProviderProtocol.DEEPSEEK_MESSAGES -> "DeepSeek Messages"
}

/**
 * 协议可选项（决策 48）：官方那套 Messages 扩展只对 DeepSeek 官方有意义，
 * 其余服务商只给通用两种，避免选出「看起来更多、其实不发」的选项。
 */
private fun protocolOptions(kind: ProviderKind): List<ProviderProtocol> = when (kind) {
    ProviderKind.DEEPSEEK_OFFICIAL -> listOf(
        ProviderProtocol.DEEPSEEK_MESSAGES,
        ProviderProtocol.ANTHROPIC_MESSAGES,
        ProviderProtocol.OPENAI_CHAT,
    )
    else -> listOf(ProviderProtocol.OPENAI_CHAT, ProviderProtocol.ANTHROPIC_MESSAGES)
}

@Composable
internal fun EditorField(
    label: String,
    value: String,
    enabled: Boolean,
    placeholder: String,
    onValueChange: (String) -> Unit,
    secret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = TcTheme.palette
    Column {
        Text(label, style = TcTypography.labelMedium, color = palette.labelSecondary)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .border(1.dp, palette.borderL2, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TcTypography.bodyMedium.copy(color = palette.labelPrimary),
                cursorBrush = SolidColor(palette.brand),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, style = TcTypography.bodyMedium, color = palette.labelTertiary)
                    }
                    inner()
                },
            )
            trailing?.invoke()
        }
    }
}

private val ID_REGEX = Regex("^[a-z][a-z0-9-]{1,31}$")

// Existing installations may have these verifier messages saved in their provider records.
private fun Context.providerError(message: String): String {
    val resource = when (message) {
        "尚未配置 API Key", "请先填写 API Key" -> R.string.prefs_detail_provider_key_missing
        "Key 无效", "API Key 无效，请检查后重试" -> R.string.prefs_detail_provider_key_invalid
        "账户余额不足，充值后即可继续" -> R.string.prefs_detail_provider_key_balance
        "操作太频繁，稍后再试" -> R.string.prefs_detail_provider_rate_limited
        "服务异常，请稍后再试" -> R.string.prefs_detail_provider_server_error
        "无法连接网络，请检查网络设置后重试" -> R.string.prefs_detail_provider_network_error
        "未获取到模型列表，可手动添加", "当前接口地址未返回可用模型列表" ->
            R.string.prefs_detail_provider_models_missing
        "模型列表格式无法解析" -> R.string.prefs_detail_provider_models_invalid
        else -> null
    }
    if (resource != null) return getString(resource)

    val httpError = ProviderHttpError.matchEntire(message) ?: return message
    val (operation, code) = httpError.destructured
    val httpResource = if (operation == "连接失败") {
        R.string.prefs_detail_provider_http_error
    } else {
        R.string.prefs_detail_provider_models_http_error
    }
    return getString(httpResource, code)
}

private val ProviderHttpError = Regex("""(连接失败|获取模型列表失败)（([0-9]{3})）""")
