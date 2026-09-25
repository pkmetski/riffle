package com.riffle.feature.source.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.riffle.feature.designsystem.TestTags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One message waiting to be shown. [id] is monotonic so a repeat of the same text re-shows. */
data class TransientMessage(val id: Long, val text: String)

/**
 * A screen's queue of transient messages — the model behind [RiffleSnackbarHost].
 *
 * Deliberately Compose-free so the queueing rules are assertable from `commonTest` (and therefore
 * on iOS): a `SnackbarHostState` can only be driven from a composition, which is exactly why iOS
 * ended up with zero transient feedback of any kind while Android had twelve `SnackbarHost`s.
 *
 * Semantics: messages are shown one at a time, oldest first. Posting while one is showing queues
 * behind it rather than dropping it, because the messages this carries are download and import
 * outcomes — losing one means an operation failed silently.
 */
@Stable
class TransientMessages {
    private val _queue = MutableStateFlow<List<TransientMessage>>(emptyList())

    /** Everything posted and not yet consumed, oldest first. */
    val queue: StateFlow<List<TransientMessage>> = _queue.asStateFlow()

    private var nextId = 1L

    /** The message the host should be displaying right now, or null when the queue is empty. */
    val current: TransientMessage? get() = _queue.value.firstOrNull()

    /** Posts [text]. Returns the id assigned, so a caller can [consume] it directly. */
    fun show(text: String): Long {
        val id = nextId++
        _queue.update { it + TransientMessage(id, text) }
        return id
    }

    /**
     * Drops [id] from the queue. A no-op when [id] is not queued (the host can race a caller that
     * already cleared it), which keeps double-dismissal from eating the *next* message.
     */
    fun consume(id: Long) {
        _queue.update { queued -> queued.filterNot { it.id == id } }
    }

    /** Drops everything — for a screen that is going away mid-queue. */
    fun clear() {
        _queue.value = emptyList()
    }
}

/** Remembers a [TransientMessages] for the current screen. */
@Composable
fun rememberTransientMessages(): TransientMessages = remember { TransientMessages() }

/**
 * Renders [messages] as a material3 snackbar pinned to the bottom of the enclosing [Box].
 *
 * Call it as the last child of a `Box` that fills the screen. Hosts with a material3 `Scaffold`
 * can pass this queue's [TransientMessages.queue] into their own `snackbarHost` instead; this
 * overlay exists because the shared screens are plain `Column`s with no Scaffold.
 */
@Composable
fun BoxScope.RiffleSnackbarHost(
    messages: TransientMessages,
    modifier: Modifier = Modifier,
) {
    val hostState = remember { SnackbarHostState() }
    val queue by messages.queue.collectAsState()
    val head = queue.firstOrNull()
    LaunchedEffect(head?.id) {
        val message = head ?: return@LaunchedEffect
        // showSnackbar suspends for the whole visible duration, so consuming afterwards advances
        // the queue exactly once per message.
        hostState.showSnackbar(message.text)
        messages.consume(message.id)
    }
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
            .align(Alignment.BottomCenter)
            .testTag(TestTags.SNACKBAR_HOST),
        snackbar = { data -> Snackbar(snackbarData = data) },
    )
}

/**
 * Convenience wrapper for a screen that is not already inside a `Box`: fills the available space,
 * renders [content], and overlays the snackbar.
 */
@Composable
fun RiffleMessageScaffold(
    messages: TransientMessages,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // The inner Box propagates min constraints so wrapping an existing screen does not
        // change its layout: without it a child that does not call fillMaxSize itself would
        // collapse to its intrinsic size in the top-left corner instead of filling the window
        // as it did before. The host stays a sibling with ordinary (wrap) constraints, or the
        // snackbar would be laid out full-screen and drawn at the top.
        Box(modifier = Modifier.fillMaxSize(), propagateMinConstraints = true) { content() }
        RiffleSnackbarHost(messages)
    }
}
