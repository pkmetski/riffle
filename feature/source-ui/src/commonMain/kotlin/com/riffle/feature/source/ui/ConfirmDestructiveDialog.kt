package com.riffle.feature.source.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_cancel
import org.jetbrains.compose.resources.stringResource

/**
 * The one "are you sure?" dialog in the app.
 *
 * Every destructive action — remove all downloads, clear the cache, remove a source, remove a
 * local folder — asks through this, so the two hosts cannot disagree about whether an action is
 * guarded. Before this existed, Android wrapped `removeAllDownloads()` in an `AlertDialog` and the
 * shared (iOS) screen fired the identical call straight off a bare `clickable`.
 *
 * [confirmLabel] is the verb, not "OK": the button says what will happen. [testTag] is applied to
 * the confirm button so a UI test can press it without matching on localised text.
 */
@Composable
fun ConfirmDestructiveDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    testTag: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    // Dismiss first: the caller's `onConfirm` routinely removes the very row that
                    // owns this dialog's state, and a dialog whose state holder has left the
                    // composition never gets its own dismissal.
                    onDismiss()
                    onConfirm()
                },
                modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
            ) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.ui_cancel)) }
        },
    )
}
