package com.riffle.app.navigation

import com.riffle.core.domain.SourceType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression: chitanka series/collection ids contain slashes (`serie/foo`, per ADR 0042) so the
 * id MUST be URL-encoded before being spliced into the fixed `series_detail/{libraryId}/{seriesId}/
 * {seriesName}` template. Without encoding the extra `/` slots break the route match and
 * NavController throws `IllegalArgumentException: destination cannot be found in the navigation
 * graph` on tap (see the AVD-5556 crash on "Австралийски квартет" -> `serie/the-australian-quartet`).
 *
 * These tests would flip red if either route builder stopped URL-encoding the id — pinning the
 * fix at the JVM level without needing a NavController.
 */
class DetailRouteEncodingTest {

    @Test
    fun `seriesDetailRoute encodes slashes in the seriesId path segment`() {
        val route = seriesDetailRoute(
            libraryId = "books",
            seriesId = "serie/the-australian-quartet",
            seriesName = "Австралийски квартет",
        )
        // 4 path segments (series_detail + 3 args): id's slash is encoded to %2F so it doesn't
        // splay into extra segments; the Cyrillic name is UTF-8 percent-encoded.
        val segments = route.split("/")
        assertEquals(4, segments.size)
        assertEquals("series_detail", segments[0])
        assertEquals("books", segments[1])
        assertEquals("serie%2Fthe-australian-quartet", segments[2])
        assertEquals(
            "%D0%90%D0%B2%D1%81%D1%82%D1%80%D0%B0%D0%BB%D0%B8%D0%B9%D1%81%D0%BA%D0%B8+%D0%BA%D0%B2%D0%B0%D1%80%D1%82%D0%B5%D1%82",
            segments[3],
        )
    }

    @Test
    fun `collectionDetailRoute encodes slashes in the collectionId path segment`() {
        val route = collectionDetailRoute(
            libraryId = "books",
            collectionId = "collection/some/nested/id",
            collectionName = "Училищна програма",
        )
        val segments = route.split("/")
        assertEquals(4, segments.size)
        assertEquals("collection_detail", segments[0])
        assertEquals("collection%2Fsome%2Fnested%2Fid", segments[2])
    }

    // ─── libraryEntryRoute: generic dispatch driven by SourceType.isUnboundedCatalog ────────────
    //
    // Regression: cold-launching into a Chitanka library materialised the
    // library_items screen (which only ever shows the handful of already-opened rows) instead of
    // the remote-browse screen, presenting as "library only shows 1 item". Fixed by centralising
    // the dispatch and driving it off SourceType.isUnboundedCatalog rather than an enum
    // comparison at every call site — a new unbounded Source (OPDS, Gutenberg) only has to flip
    // the flag on its enum entry.

    @Test
    fun `libraryEntryRoute for a Room-mirrored source lands on library_items`() {
        val route = libraryEntryRoute(SourceType.ABS, "lib-1", "Fiction")
        assertEquals("library_items/lib-1/Fiction", route)
    }

    @Test
    fun `libraryEntryRoute for LOCAL_FILES lands on library_items`() {
        val route = libraryEntryRoute(SourceType.LOCAL_FILES, "lib-1", "Downloads")
        assertEquals("library_items/lib-1/Downloads", route)
    }

    @Test
    fun `libraryEntryRoute for an unbounded catalogue lands on the remote-browse screen`() {
        val route = libraryEntryRoute(SourceType.CHITANKA, "books", "Chitanka")
        assertEquals("chitanka_browse/books/Chitanka", route)
    }

    @Test
    fun `libraryEntryRoute encodes the library name`() {
        val route = libraryEntryRoute(SourceType.ABS, "lib-1", "Български")
        // The encoded segment must not contain literal Cyrillic — nav route templates only match
        // path-safe strings.
        assertEquals(
            "library_items/lib-1/%D0%91%D1%8A%D0%BB%D0%B3%D0%B0%D1%80%D1%81%D0%BA%D0%B8",
            route,
        )
    }

    @Test
    fun `libraryEntryRoute with null source type falls back to library_items`() {
        // activeServer hasn't resolved yet on cold start — take the safer bounded UX; the drawer
        // will correct on the next selection once the source resolves.
        val route = libraryEntryRoute(null, "lib-1", "Fiction")
        assertEquals("library_items/lib-1/Fiction", route)
    }

    @Test
    fun `SourceType isUnboundedCatalog flag is set exactly for CHITANKA`() {
        // The flag is what the dispatch keys on — if a future source gets the flag by accident,
        // the whole entry point would silently switch to remote-browse.
        assertEquals(false, SourceType.ABS.isUnboundedCatalog)
        assertEquals(false, SourceType.LOCAL_FILES.isUnboundedCatalog)
        assertEquals(true, SourceType.CHITANKA.isUnboundedCatalog)
    }

    @Test
    fun `slash-free ids pass through unchanged (except URL-safe reencoding)`() {
        // ABS series ids are UUIDs with no reserved characters — encoding is a no-op for them.
        val route = seriesDetailRoute(
            libraryId = "lib-1",
            seriesId = "b3e5d5f0-1234",
            seriesName = "Foo",
        )
        assertEquals("series_detail/lib-1/b3e5d5f0-1234/Foo", route)
    }
}
