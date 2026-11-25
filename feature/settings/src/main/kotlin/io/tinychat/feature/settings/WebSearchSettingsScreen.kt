package io.tinychat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.tinychat.designsystem.TcIcons
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcTypography
import io.tinychat.model.WebSearchCredentials
import io.tinychat.model.WebSearchProvider
import io.tinychat.ui.TcButton
import io.tinychat.ui.TcIconButton
import io.tinychat.ui.TcListRow
import io.tinychat.ui.TcTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun WebSearchSettingsScreen(
    provider: WebSearchProvider,
    credentials: WebSearchCredentials,
    onBack: () -> Unit,
    onSave: suspend (WebSearchProvider, Map<WebSearchProvider, String>) -> Unit,
) {
    val palette = TcTheme.palette
    val scope = rememberCoroutineScope()
    var picked by remember(provider) { mutableStateOf(provider) }
    var keyEdits by remember { mutableStateOf<Map<WebSearchProvider, String>>(emptyMap()) }
    var reveal by remember(picked) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val changed = picked != provider || keyEdits.isNotEmpty()
    val key = keyEdits[picked].orEmpty()
    val clearKey = keyEdits[picked] == ""
    val keySaved = picked in credentials.savedKeys
    val hasEffectiveKey = keyEdits[picked]?.isNotBlank() ?: credentials.hasKey(picked)

    Column(Modifier.fillMaxSize().background(palette.bg)) {
        TcTopBar(title = stringResource(R.string.prefs_detail_search_title), leading = { TcIconButton(TcIcons.Back, stringResource(R.string.prefs_detail_back), onBack) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            for (option in WebSearchProvider.entries) {
                TcListRow(
                    title = option.label,
                    trailing = if (picked == option) {
                        { Icon(TcIcons.Check, stringResource(R.string.prefs_detail_selected), tint = palette.brand) }
                    } else {
                        null
                    },
                    onClick = if (saving) null else ({ picked = option }),
                )
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                EditorField(
                    label = stringResource(R.string.prefs_detail_search_key, picked.label),
                    value = key,
                    enabled = !saving,
                    placeholder = when {
                        keySaved && !clearKey -> stringResource(R.string.prefs_detail_saved)
                        picked == WebSearchProvider.DEEPSEEK && credentials.canReuseDeepSeekKey -> stringResource(R.string.prefs_detail_search_key_reuse)
                        picked == WebSearchProvider.ANYSEARCH -> stringResource(R.string.prefs_detail_search_key_optional)
                        else -> ""
                    },
                    onValueChange = { input ->
                        val trimmed = input.trim()
                        keyEdits = if (trimmed.isEmpty()) keyEdits - picked else keyEdits + (picked to trimmed)
                    },
                    secret = !reveal,
                    trailing = {
                        Text(
                            if (reveal) stringResource(R.string.prefs_detail_hide) else stringResource(R.string.prefs_detail_show),
                            style = TcTypography.bodyMedium,
                            color = palette.labelSecondary,
                            modifier = Modifier.clickable { reveal = !reveal }.padding(start = 10.dp),
                        )
                    },
                )
                if (keySaved && key.isBlank()) {
                    Text(
                        text = if (clearKey) stringResource(R.string.prefs_detail_search_undo_clear) else stringResource(R.string.prefs_detail_search_clear_key),
                        style = TcTypography.labelMedium,
                        color = if (clearKey) palette.brand else palette.danger,
                        modifier = Modifier
                            .clickable(enabled = !saving) {
                                if (clearKey) {
                                    keyEdits = keyEdits - picked
                                } else {
                                    keyEdits = keyEdits + (picked to "")
                                    if (picked.needsOwnKey) picked = WebSearchProvider.DEEPSEEK
                                }
                            }
                            .padding(vertical = 10.dp),
                    )
                }
            }
            if (saveFailed) {
                Text(
                    stringResource(R.string.prefs_detail_search_save_retry),
                    style = TcTypography.labelMedium,
                    color = palette.danger,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        TcButton(
            text = if (saving) stringResource(R.string.prefs_detail_saving) else stringResource(R.string.prefs_detail_save),
            onClick = {
                scope.launch {
                    saving = true
                    saveFailed = false
                    try {
                        onSave(picked, keyEdits)
                        onBack()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        saveFailed = true
                    } finally {
                        saving = false
                    }
                }
            },
            enabled = !saving && changed && (!picked.needsOwnKey || hasEffectiveKey),
            fillWidth = true,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 16.dp),
        )
    }
}
