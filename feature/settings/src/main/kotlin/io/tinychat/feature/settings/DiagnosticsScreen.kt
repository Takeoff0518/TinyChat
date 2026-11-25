package io.tinychat.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.tinychat.designsystem.TcIcons
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcTypography
import io.tinychat.ui.TcAlertDialog
import io.tinychat.ui.TcButton
import io.tinychat.ui.TcButtonVariant
import io.tinychat.ui.TcIconButton
import io.tinychat.ui.TcListRow
import io.tinychat.ui.TcTopBar

/** 诊断信息列表项：时间与现场由 app 侧从报告里解析后传进来。 */
data class DiagnosticsItem(
    val id: String,
    val time: String,
    val scene: String,
)

/**
 * 诊断信息（M9-2）。
 *
 * 崩溃兜底把报告写进 `filesDir/crashes`，这一页负责让用户看得见、能复制、能删——
 * 不加这个入口，那些文件写进沙盒等于石沉大海。
 *
 * 读写全在 app 侧（`CrashReporter` 在 `:app` 模块），这里只接数据与回调，不认识文件。
 */
@Composable
fun DiagnosticsScreen(
    items: List<DiagnosticsItem>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    val palette = TcTheme.palette
    var confirmClearAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onRefresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg),
    ) {
        TcTopBar(
            title = stringResource(R.string.prefs_detail_diagnostics),
            leading = { TcIconButton(TcIcons.Back, stringResource(R.string.prefs_detail_back), onBack) },
            trailing = {
                if (items.isNotEmpty()) {
                    TextButton(onClick = { confirmClearAll = true }) {
                        Text(stringResource(R.string.prefs_detail_clear), color = palette.labelSecondary, style = TcTypography.labelLarge)
                    }
                }
            },
        )
        if (items.isEmpty()) {
            EmptyDiagnostics()
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    TcListRow(
                        title = item.time,
                        subtitle = item.scene,
                        onClick = { onOpen(item.id) },
                    )
                }
            }
        }
    }

    if (confirmClearAll) {
        ClearDiagnosticsDialog(
            onDismiss = { confirmClearAll = false },
            onClear = {
                confirmClearAll = false
                onClearAll()
                onRefresh()
            },
        )
    }
}

@Composable
fun DiagnosticReportScreen(
    time: String?,
    body: String?,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    val palette = TcTheme.palette
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(palette.bg)) {
        TcTopBar(
            title = stringResource(R.string.prefs_detail_diagnostics),
            leading = { TcIconButton(TcIcons.Back, stringResource(R.string.prefs_detail_back), onBack) },
        )
        if (body == null) {
            EmptyDiagnostics()
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (time != null) {
                    Text(time, style = TcTypography.labelMedium, color = palette.labelTertiary)
                    Spacer(Modifier.height(8.dp))
                }
                Text(body, style = TcTypography.bodySmall, color = palette.labelPrimary)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TcButton(
                    text = stringResource(R.string.prefs_detail_diagnostics_copy),
                    onClick = { copyToClipboard(context, body) },
                    modifier = Modifier.weight(1f),
                )
                TcButton(
                    text = stringResource(R.string.prefs_detail_clear),
                    onClick = { confirmClear = true },
                    modifier = Modifier.weight(1f),
                    variant = TcButtonVariant.Outline,
                )
            }
        }
    }

    if (confirmClear) {
        ClearDiagnosticsDialog(
            onDismiss = { confirmClear = false },
            onClear = {
                confirmClear = false
                onClear()
            },
        )
    }
}

@Composable
private fun EmptyDiagnostics() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.prefs_detail_diagnostics_empty),
            style = TcTypography.bodyMedium,
            color = TcTheme.palette.labelTertiary,
        )
    }
}

@Composable
private fun ClearDiagnosticsDialog(onDismiss: () -> Unit, onClear: () -> Unit) {
    val palette = TcTheme.palette
    TcAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prefs_detail_diagnostics_clear)) },
        text = { Text(stringResource(R.string.prefs_detail_diagnostics_clear_warning)) },
        confirmButton = {
            TextButton(onClick = onClear) { Text(stringResource(R.string.prefs_detail_clear), color = palette.danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.prefs_detail_cancel), color = palette.labelSecondary) }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText(context.getText(R.string.prefs_detail_diagnostics_clip), text))
    Toast.makeText(context, R.string.prefs_detail_diagnostics_copied, Toast.LENGTH_SHORT).show()
}
