package com.riffle.app.navigation

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
