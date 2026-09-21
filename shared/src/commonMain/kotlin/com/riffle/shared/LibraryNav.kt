package com.riffle.shared

import androidx.compose.runtime.Composable
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.FacetType
import com.riffle.feature.library.LibrarySectionType
import com.riffle.shared.audiobook.AudiobookPlayerScreen
import com.riffle.shared.reader.CbzReaderScreen
import com.riffle.shared.reader.EpubReaderScreen
import com.riffle.shared.reader.PdfReaderScreen

/**
 * Destinations inside one library-browsing host.
 *
 * Both iOS hosts navigate with this type: `LibraryHost` (the per-library browser reached from the
 * drawer) and `RiffleScreen` (the cross-source hub). Keeping one vocabulary is what lets them
 * share [readerNavForItem] and [ReaderHost] instead of each spelling the reader routing out — the
 * hub previously had no routing at all, so its detail sheet's Read button only dismissed itself.
 */
internal sealed interface LibraryNav {
    data object Items : LibraryNav
    data class Section(val sectionType: LibrarySectionType) : LibraryNav

    /**
     * The detail sheet. Keyed by ids rather than by a [LibraryItem] so surfaces that only know
     * `(sourceId, itemId)` — the Annotations tab's annotated books — can open the same sheet.
     * The sheet loads the item itself and hands it back through its `onRead` callback.
     */
    data class ItemDetail(val itemId: String, val sourceId: String?) : LibraryNav
    data class SeriesDetail(val seriesId: String, val seriesLibraryId: String, val seriesName: String) : LibraryNav
    data class CollectionDetail(val collectionId: String, val collectionLibraryId: String, val collectionName: String) : LibraryNav

    /**
     * A playlist's contents. [playlistLibraryId] is the ABS root the playlist is scoped to — the
     * `rootId` every [com.riffle.core.domain.PlaylistsRepository] call takes — and is carried
     * separately from the id so "Play" can hand both to the player as auto-advance context.
     */
    data class PlaylistDetail(
        val playlistId: String,
        val playlistName: String,
        val playlistLibraryId: String,
    ) : LibraryNav

    /**
     * The books in [facetLibraryId] matching one metadata facet — an author, a genre, a year, a
     * language, or "has a readaloud". Android reaches the same screen through
     * `filtered_books/{libraryId}/{facetType}/{facetValue}`.
     */
    data class FilteredBooks(
        val facetLibraryId: String,
        val facetType: FacetType,
        val facetValue: String,
    ) : LibraryNav

    /**
     * A destination that opens the book itself. Grouped so every host renders them through the
     * single [ReaderHost] `when` rather than repeating the four-way dispatch.
     */
    sealed interface ReaderDestination : LibraryNav {
        val item: LibraryItem
    }

    data class Reader(override val item: LibraryItem) : ReaderDestination
    data class PdfReader(override val item: LibraryItem) : ReaderDestination
    data class CbzReader(override val item: LibraryItem) : ReaderDestination

    /**
     * The audiobook player. [playlistId] / [playlistLibraryId] are non-null only when the player
     * was opened from [PlaylistDetail]; they are what lets `AudiobookPlayerViewModel` auto-advance
     * to the next item at end-of-book, exactly as Android's `?playlistId=…&libraryId=…` query
     * args do. Before the playlist surfaces existed no iOS caller could supply them, so the
     * ViewModel's `navPlaylistId` was hard-wired to `null` and `PlaylistAdvance` was unreachable.
     */
    data class AudiobookPlayer(
        override val item: LibraryItem,
        val playlistId: String? = null,
        val playlistLibraryId: String? = null,
    ) : ReaderDestination
}

/**
 * The reader/player [item] opens, or `null` when no iOS surface can render it. Callers keep the
 * current destination on `null` rather than dismissing, so "Read" on an unsupported format is
 * inert instead of silently closing the sheet.
 */
internal fun readerNavForItem(item: LibraryItem): LibraryNav.ReaderDestination? = when {
    item.isListenable -> LibraryNav.AudiobookPlayer(item)
    item.ebookFormat == EbookFormat.Pdf -> LibraryNav.PdfReader(item)
    item.ebookFormat == EbookFormat.Cbz -> LibraryNav.CbzReader(item)
    item.isReadable -> LibraryNav.Reader(item)
    else -> null
}

/**
 * Resolves the reader destination for [item] **and records the open** (#1071 §17).
 *
 * `RecordItemOpened` was bound and injected on iOS but `markOpened()` had zero callers, so two
 * things silently never happened: the local `lastOpenedAt` bump that orders the In Progress row,
 * and the `touchOpenTimestamp` push that lets the user's other devices see the open through ABS's
 * `mediaProgress.lastUpdate`. Android calls `viewModel.markOpened()` from each of the three layout
 * branches of its detail screen (`LibraryItemDetailScreen.kt:434,470,506`); iOS routes every
 * format and both hosts through [readerNavForItem], so recording here covers EPUB, PDF, CBZ and
 * audiobooks, from the per-library browser and from the Riffle hub, in one place.
 *
 * The record runs on [applicationScope] rather than being awaited: `RecordItemOpened` ends in a
 * best-effort network PATCH, and blocking the reader on that round-trip would be a visible stall.
 * Survivable rather than composition-scoped so the PATCH is not cancelled by the very navigation
 * that triggered it.
 *
 * Returns `null` — and records nothing — for an item no iOS reader can open.
 */
internal fun openItemForReading(
    item: LibraryItem,
    applicationScope: ApplicationScope,
    recordItemOpened: suspend (itemId: String) -> Unit,
): LibraryNav.ReaderDestination? {
    val destination = readerNavForItem(item) ?: return null
    applicationScope.launchSurvivable { runCatching { recordItemOpened(item.id) } }
    return destination
}

/**
 * The player destination for "Play" on a playlist detail screen.
 *
 * Extracted from the host's `when` so the one thing that makes iOS playlist auto-advance work at
 * all — that the playlist's id **and** its root ride along into the player — is pinned by a test
 * rather than living only inside a Composable. With either field dropped
 * `AudiobookPlayerViewModel.nextInPlaylist()` returns null and the chain silently stops after one
 * book.
 */
internal fun playlistPlayerNav(
    item: LibraryItem,
    from: LibraryNav.PlaylistDetail,
): LibraryNav.AudiobookPlayer = LibraryNav.AudiobookPlayer(
    item = item,
    playlistId = from.playlistId,
    playlistLibraryId = from.playlistLibraryId,
)

/**
 * Where the host goes when an audiobook opened from a playlist reaches its end.
 *
 * [next] is the resolved next item, or `null` when the playlist referenced an id the library no
 * longer has. The playlist context is carried forward so the *next* book can advance too —
 * dropping it here would make auto-advance fire exactly once.
 */
internal fun playlistAdvanceNav(
    next: LibraryItem?,
    from: LibraryNav.AudiobookPlayer,
): LibraryNav = when {
    next == null -> LibraryNav.Items
    else -> LibraryNav.AudiobookPlayer(
        item = next,
        playlistId = from.playlistId,
        playlistLibraryId = from.playlistLibraryId,
    )
}

/**
 * Renders [destination]. The single place the four reader surfaces are constructed.
 *
 * [onPlaylistAdvance] fires when an audiobook opened from a playlist reaches its end and the
 * ViewModel has found the next item; the host resolves that id to a [LibraryItem] and navigates.
 * It can only fire for a destination carrying a playlist context, so a host that never builds one
 * passes a no-op — the same arrangement as Android's defaulted `onPlaylistAdvance`.
 */
@Composable
internal fun ReaderHost(
    destination: LibraryNav.ReaderDestination,
    onBack: () -> Unit,
    onPlaylistAdvance: (sourceId: String, nextItemId: String) -> Unit,
) {
    when (destination) {
        is LibraryNav.Reader -> EpubReaderScreen(item = destination.item, onBack = onBack)
        is LibraryNav.PdfReader -> PdfReaderScreen(item = destination.item, onBack = onBack)
        is LibraryNav.CbzReader -> CbzReaderScreen(item = destination.item, onBack = onBack)
        is LibraryNav.AudiobookPlayer -> AudiobookPlayerScreen(
            item = destination.item,
            playlistId = destination.playlistId,
            playlistLibraryId = destination.playlistLibraryId,
            onBack = onBack,
            onPlaylistAdvance = onPlaylistAdvance,
        )
    }
}
