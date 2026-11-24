package io.tinychat.feature.chat.share

import androidx.compose.ui.res.stringResource
import io.tinychat.feature.chat.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcTypography
import java.io.File
import io.tinychat.ui.TcAlertDialog

/**
 * 分享抽屉（M8-2 / 02 §12.9）：生成长图 / 复制文本 / 分享文本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onGenerateImage: () -> Unit,
    onCopyText: () -> Unit,
    onShareText: () -> Unit,
) {
    val palette = TcTheme.palette
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.bg,
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.chat_share), style = TcTypography.titleMedium, color = palette.labelPrimary)
            Spacer(Modifier.height(10.dp))
            ShareRow(stringResource(R.string.chat_create_image), null, onGenerateImage)
            ShareRow(stringResource(R.string.chat_copy_text), null, onCopyText)
            ShareRow(stringResource(R.string.chat_share_text), null, onShareText)
        }
    }
}

@Composable
private fun ShareRow(title: String, subtitle: String?, onClick: () -> Unit) {
    val palette = TcTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(title, style = TcTypography.bodyLarge, color = palette.labelPrimary)
        if (subtitle != null) {
            Text(subtitle, style = TcTypography.labelMedium, color = palette.labelTertiary)
        }
    }
}

/** 生成中：一个不能被误触关掉的转圈对话框。 */
@Composable
fun ShareProgressDialog() {
    val palette = TcTheme.palette
    TcAlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), color = palette.brand)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.chat_creating_image), style = TcTypography.bodyMedium, color = palette.labelPrimary)
            }
        },
    )
}

/** 生成完的预览：能先看一眼再决定发不发。 */
@Composable
fun SharePreviewDialog(
    output: ShareCapture.Output,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = TcTheme.palette
    TcAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (output.pages > 1) stringResource(R.string.chat_share_image_count, output.pages) else stringResource(R.string.chat_share_image),
                style = TcTypography.titleMedium,
                color = palette.labelPrimary,
            )
        },
        text = {
            Image(
                bitmap = output.preview.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        },
        confirmButton = {
            TextButton(onClick = onShare) { Text(stringResource(R.string.chat_share)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel), color = TcTheme.palette.labelSecondary) }
        },
    )
}

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.chat_share_response)))
}

fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.chat_response), text))
}

fun shareImages(context: Context, files: List<File>) {
    val uris = files.map {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/png"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.chat_share_image)))
}
