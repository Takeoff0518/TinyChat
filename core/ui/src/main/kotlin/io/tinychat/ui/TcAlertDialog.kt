package io.tinychat.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.tinychat.designsystem.TcTheme

@Composable
fun TcAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = TcTheme.palette.bgLayer2,
) {
    // The dialog window starts a new owner with the system locale. Keep the app's locale in its content.
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    fun localized(content: @Composable () -> Unit): @Composable () -> Unit = {
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, content = content)
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = localized(confirmButton),
        dismissButton = dismissButton?.let(::localized),
        title = title?.let(::localized),
        text = text?.let(::localized),
        containerColor = containerColor,
        titleContentColor = TcTheme.palette.labelPrimary,
        textContentColor = TcTheme.palette.labelSecondary,
        tonalElevation = 0.dp,
    )
}
