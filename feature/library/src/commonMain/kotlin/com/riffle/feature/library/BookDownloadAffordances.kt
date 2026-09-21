package com.riffle.feature.library

import com.riffle.core.models.LibraryItem

/**
 * Which per-format download controls an item detail surface shows, and whether each is tappable.
 *
 * This is the one place the rule lives, so Android's action row and the shared
 * `BookDownloadControls` cannot disagree about when a download affordance appears. Previously the
 * rule existed only inside Android's `ActionRow` composable and iOS rendered no download control
 * at all; lifting it out is what makes the decision assertable from `commonTest` (and therefore
 * from `iosSimulatorArm64Test`).
 */
data class BookDownloadAffordances(
    val showEbook: Boolean,
    val ebookEnabled: Boolean,
    val showAudiobook: Boolean,
    val audiobookEnabled: Boolean,
    val showReadaloud: Boolean,
    val readaloudEnabled: Boolean,
) {
    /** True when at least one download control is rendered — the caller can skip the whole row. */
    val any: Boolean get() = showEbook || showAudiobook || showReadaloud

    companion object {
        val None = BookDownloadAffordances(
            showEbook = false,
            ebookEnabled = false,
            showAudiobook = false,
            audiobookEnabled = false,
            showReadaloud = false,
            readaloudEnabled = false,
        )
    }
}

/**
 * Derives the download affordances for [item].
 *
 * - The ebook control needs [DetailCapabilities.hasDownloads] and a readable item; Sources with no
 *   local store (LocalFiles today) hide every download button.
 * - The audiobook control additionally needs the item to be listenable and the ViewModel to have
 *   decided the item has an audiobook at all ([audiobookDownloadState] non-null).
 * - The readaloud control is gated on [DetailCapabilities.hasReadaloud] and on a matched
 *   Storyteller link ([readaloudDownloadState] non-null) — *not* on `hasDownloads`, matching the
 *   Android action row.
 *
 * Offline blocks a control only when nothing is stored locally yet, because a download that has
 * already landed can still be removed with no network.
 */
fun bookDownloadAffordances(
    item: LibraryItem,
    capabilities: DetailCapabilities,
    isOffline: Boolean,
    audiobookDownloadState: DownloadState?,
    readaloudDownloadState: DownloadState?,
): BookDownloadAffordances = BookDownloadAffordances(
    showEbook = capabilities.hasDownloads && item.isReadable,
    ebookEnabled = true,
    showAudiobook = capabilities.hasDownloads && item.isListenable && audiobookDownloadState != null,
    audiobookEnabled = !(isOffline && audiobookDownloadState == DownloadState.NotDownloaded),
    showReadaloud = capabilities.hasReadaloud && readaloudDownloadState != null,
    readaloudEnabled = !(isOffline && readaloudDownloadState == DownloadState.NotDownloaded),
)

/** What a finished download run did. */
enum class BookDownloadOutcome { Completed, Failed }

/**
 * Classifies a [DownloadState] transition so a screen can say something when a download ends.
 *
 * A download that fails leaves no trace in the UI: [DownloadManager] catches the throw and puts
 * the key back into a terminal state, so the ring simply turns back into an outlined circle and
 * the user is left to guess. The only observable difference between "finished" and "failed" is
 * which terminal state it landed in, which is what this reads.
 *
 * Returns null for every transition that is not the end of a run — including the very first
 * emission, where [previous] is null.
 */
fun bookDownloadOutcome(previous: DownloadState?, current: DownloadState): BookDownloadOutcome? {
    if (previous !is DownloadState.InProgress) return null
    return when (current) {
        DownloadState.Downloaded -> BookDownloadOutcome.Completed
        // Cached counts as a failure of *this* run: the user asked for a durable copy and the
        // item fell back to the evictable cache it may well have already been in.
        DownloadState.Cached, DownloadState.NotDownloaded -> BookDownloadOutcome.Failed
        is DownloadState.InProgress -> null
    }
}
