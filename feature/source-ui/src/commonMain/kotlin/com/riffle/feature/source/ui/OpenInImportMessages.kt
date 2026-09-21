package com.riffle.feature.source.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.riffle.core.data.localfiles.OpenInImportFeed
import com.riffle.core.data.localfiles.OpenInImportResult
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_import_added
import com.riffle.feature.source.ui.generated.resources.ui_import_failed
import com.riffle.feature.source.ui.generated.resources.ui_import_unsupported
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Which string an "Open in Riffle" outcome renders, and with what argument.
 *
 * A pure pair rather than a formatted string so the mapping is assertable without a composition
 * — this is the whole user-visible surface of the import feature, and "it silently did nothing"
 * is the failure mode it exists to prevent.
 */
data class OpenInImportMessage(val resource: StringResource, val argument: String)

fun openInImportMessage(result: OpenInImportResult): OpenInImportMessage = when (result) {
    is OpenInImportResult.Imported -> OpenInImportMessage(Res.string.ui_import_added, result.title)
    // A duplicate is reported with the same "added" wording on purpose: from the reader's point
    // of view the book *is* now in the library, and a separate "already there" phrasing reads as
    // an error for what is a completely successful outcome.
    is OpenInImportResult.AlreadyPresent -> OpenInImportMessage(Res.string.ui_import_added, result.title)
    is OpenInImportResult.Unsupported ->
        OpenInImportMessage(Res.string.ui_import_unsupported, result.displayName)
    is OpenInImportResult.Failed -> OpenInImportMessage(Res.string.ui_import_failed, result.displayName)
}

/**
 * Drains [feed] into [messages] so every "Open in Riffle" outcome surfaces as a snackbar.
 *
 * Mounted once, at each host's composition root — the import starts before any screen exists,
 * so a screen-scoped collector would miss the common case entirely.
 */
@Composable
fun OpenInImportMessages(feed: OpenInImportFeed, messages: TransientMessages) {
    val events by feed.events.collectAsState()
    val head = events.firstOrNull()
    // Resolved during composition, not inside the effect: `stringResource` is a @Composable and
    // cannot be called from a coroutine.
    val text = head?.let { event ->
        val message = openInImportMessage(event.result)
        stringResource(message.resource, message.argument)
    }
    LaunchedEffect(head?.id) {
        val event = head ?: return@LaunchedEffect
        messages.show(text ?: return@LaunchedEffect)
        feed.consume(event.id)
    }
}
