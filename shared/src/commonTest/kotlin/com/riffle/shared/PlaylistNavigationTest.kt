package com.riffle.shared

import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #1072 §1 — iOS had no playlist detail screen, so nothing could open the audiobook player *from*
 * a playlist. The iOS Koin factory therefore hard-wired `navPlaylistId = null`, which made
 * `AudiobookPlayerViewModel`'s end-of-book auto-advance permanently unreachable: `nextInPlaylist()`
 * returns null the moment either the playlist id or its root is missing.
 *
 * These pin the two hops that carry the context. Dropping `playlistId` or `playlistLibraryId` from
 * [playlistPlayerNav] — the revert — fails `playCarriesBothPlaylistIdsIntoThePlayer`; dropping
 * them from [playlistAdvanceNav] fails `advancingKeepsThePlaylistContextSoTheChainContinues`,
 * which is the difference between auto-advance firing once and firing for the rest of the
 * playlist.
 */
class PlaylistNavigationTest {

    private fun item(id: String) = LibraryItem(
        id = id,
        sourceId = "src-1",
        libraryId = "lib-1",
        title = "T-$id",
        author = "A",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Unsupported,
        hasAudio = true,
    )

    private val playlist = LibraryNav.PlaylistDetail(
        playlistId = "pl-7",
        playlistName = "Evening Queue",
        playlistLibraryId = "root-9",
    )

    @Test
    fun playCarriesBothPlaylistIdsIntoThePlayer() {
        val destination = playlistPlayerNav(item("book-1"), playlist)

        assertEquals("book-1", destination.item.id)
        assertEquals("pl-7", destination.playlistId)
        assertEquals("root-9", destination.playlistLibraryId)
    }

    @Test
    fun advancingKeepsThePlaylistContextSoTheChainContinues() {
        val current = playlistPlayerNav(item("book-1"), playlist)

        val next = playlistAdvanceNav(item("book-2"), current)

        assertTrue(next is LibraryNav.AudiobookPlayer)
        assertEquals("book-2", next.item.id)
        assertEquals("pl-7", next.playlistId)
        assertEquals("root-9", next.playlistLibraryId)
    }

    /** The playlist referenced an id the library no longer has: close rather than hang. */
    @Test
    fun advancingToAnItemTheLibraryNoLongerHasReturnsToTheLibrary() {
        val current = playlistPlayerNav(item("book-1"), playlist)

        assertEquals(LibraryNav.Items, playlistAdvanceNav(null, current))
    }

    /**
     * Every other entry point — a library row, the Riffle hub, a series — must leave the context
     * empty, otherwise a plain audiobook would chain into whatever playlist happened to be last.
     */
    @Test
    fun openingAnAudiobookOutsideAPlaylistCarriesNoContext() {
        val destination = readerNavForItem(item("book-1"))

        assertTrue(destination is LibraryNav.AudiobookPlayer)
        assertNull(destination.playlistId)
        assertNull(destination.playlistLibraryId)
    }
}
