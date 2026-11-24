package io.tinychat.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.tinychat.designsystem.TcIcons
import io.tinychat.designsystem.TcLogoMark
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcTypography
import io.tinychat.ui.TcIconButton
import io.tinychat.ui.TcListRow
import io.tinychat.ui.TcSectionLabel
import io.tinychat.ui.TcTopBar

/** 项目仓库地址（M9 前占位，仓库公开后即为正式地址）。 */
const val REPOSITORY_URL = "https://github.com/Takeoff0518/TinyChat"

/**
 * 关于（docs/02 §12.5）。
 *
 * 服务协议与帮助入口本轮不提供（用户决定），只保留版本、开源项目与开源许可。
 */
@Composable
fun AboutScreen(
    versionName: String,
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
) {
    val palette = TcTheme.palette
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg),
    ) {
        TcTopBar(
            title = stringResource(R.string.prefs_detail_about_title),
            leading = { TcIconButton(TcIcons.Back, stringResource(R.string.prefs_detail_back), onBack) },
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            // 头部（决策 62）：图标 / 名称 / 版本居中三行，下面的入口行保持原样
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TcLogoMark(size = 72.dp)
                Spacer(Modifier.height(16.dp))
                Text("TinyChat", style = TcTypography.headlineSmall, color = palette.labelPrimary)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (versionName.isBlank()) {
                        stringResource(R.string.prefs_detail_version_unknown)
                    } else {
                        stringResource(R.string.prefs_detail_version, versionName)
                    },
                    style = TcTypography.labelMedium,
                    color = palette.labelTertiary,
                )
            }
            TcListRow(
                title = stringResource(R.string.prefs_detail_source),
                subtitle = REPOSITORY_URL,
                onClick = { openUrl(context, REPOSITORY_URL) },
                trailing = {
                    Icon(
                        imageVector = TcIcons.ExternalLink,
                        contentDescription = null,
                        tint = palette.labelTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            TcListRow(
                title = stringResource(R.string.prefs_detail_licenses),
                onClick = onOpenLicenses,
                trailing = {
                    Icon(
                        imageVector = TcIcons.ChevronRight,
                        contentDescription = null,
                        tint = palette.labelTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            // 崩溃兜底写下来的诊断信息（M9-2）：不放这个入口，报告就只躺在沙盒里
            TcListRow(
                title = stringResource(R.string.prefs_detail_diagnostics),
                onClick = onOpenDiagnostics,
                trailing = {
                    Icon(
                        imageVector = TcIcons.ChevronRight,
                        contentDescription = null,
                        tint = palette.labelTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 开源许可清单（按许可证分组）。 */
@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val palette = TcTheme.palette
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.bg),
    ) {
        TcTopBar(
            title = stringResource(R.string.prefs_detail_licenses),
            leading = { TcIconButton(TcIcons.Back, stringResource(R.string.prefs_detail_back), onBack) },
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            TcSectionLabel("Apache License 2.0")
            LicenseRow(stringResource(R.string.prefs_detail_kotlin_stdlib))
            ApacheComponents.forEach { LicenseRow(it) }

            TcSectionLabel("MIT License")
            LicenseRow(
                stringResource(R.string.prefs_detail_katex_fonts),
                stringResource(R.string.prefs_detail_katex_note),
            )
            MitComponents.forEach { (name, note) -> LicenseRow(name, stringResource(note)) }

            TcSectionLabel(stringResource(R.string.prefs_detail_build_test))
            TestComponents.forEach { (name, license) -> LicenseRow(name, license) }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private val ApacheComponents = listOf(
    "kotlinx.coroutines",
    "kotlinx.serialization",
    "AndroidX (core-ktx, activity, lifecycle, navigation, Room, DataStore, core-splashscreen)",
    "Jetpack Compose (UI, Foundation, Material 3)",
    "OkHttp",
    "Coil",
    "multiplatform-markdown-renderer",
)

private val MitComponents = listOf(
    "Mermaid" to R.string.prefs_detail_mermaid_note,
    "deepseek-ai/chat-demo" to R.string.prefs_detail_chat_demo_note,
)

private val TestComponents = listOf(
    "Robolectric" to "MIT License",
    "JUnit 4" to "Eclipse Public License 1.0",
)

private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    val opened = runCatching { context.startActivity(intent) }.isSuccess
    if (!opened) {
        Toast.makeText(context, R.string.prefs_detail_browser_missing, Toast.LENGTH_SHORT).show()
    }
}

/** 许可证条目：标题允许换行，避免长组件名被省略号截断。 */
@Composable
private fun LicenseRow(name: String, note: String? = null) {
    val palette = TcTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(name, style = TcTypography.bodyLarge, color = palette.labelPrimary)
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            Text(note, style = TcTypography.labelMedium, color = palette.labelTertiary)
        }
    }
}
