package one.moveo.studywrapper.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/// Confirmation wording shared by the browser's Done and the home screen's
/// Finish study (extension popup `finishConfirm`; iOS `FinishCopy`).
object FinishCopy {
    const val TITLE = "Finish this study?"
    const val CONFIRM = "Finish"
    const val CANCEL = "Not yet"

    fun message(hasLeadOut: Boolean): String = if (hasLeadOut) {
        "Tracking stops and you'll be taken to the closing page."
    } else {
        "Tracking stops and the study is marked complete on this device."
    }
}

/// "Finish this study?" — the one dialog both entry points show (§a2.8).
/// Neutral confirm styling: finishing is the expected happy path, not a
/// destructive act (unlike Leave).
@Composable
fun FinishStudyDialog(hasLeadOut: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FinishCopy.TITLE) },
        text = { Text(FinishCopy.message(hasLeadOut)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(FinishCopy.CONFIRM, color = Brand.text) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(FinishCopy.CANCEL, color = Brand.textSecondary) }
        },
        containerColor = Brand.bgElevated,
        titleContentColor = Brand.text,
        textContentColor = Brand.textSecondary,
    )
}
