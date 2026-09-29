package com.riffle.shared.source

import com.riffle.core.catalog.chitanka.ChitankaCatalog
import com.riffle.core.models.SourceType
import com.riffle.feature.library.tabIndexForAnnotations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for #1071 §17 — the iOS library host had no source-type fork.
 *
 * `HomeScreen.LibraryHost` rendered `LibraryItemsScreen` for every library. Unbounded catalogues
 * are network-only (ADR 0051) and have no `library_items` rows, so Chitanka, Gutenberg and
 * radio.es each opened a permanently empty library with no error. Android has always forked here,
 * in `NavRoutes.libraryEntryRoute`, off the same `SourceType.isUnboundedCatalog` flag.
 *
 * [shouldRenderUnboundedBrowse] is that fork, lifted out of the composition so it can be
 * asserted. Restore the old `LibraryHost` body — an unconditional `LibraryItemsScreen` — and
 * `rendersTheBrowseScreenForEveryUnboundedCatalogue` goes red.
 */
class UnboundedBrowseRoutingTest {

    @Test
    fun rendersTheBrowseScreenForEveryUnboundedCatalogue() {
        listOf(SourceType.CHITANKA, SourceType.GUTENBERG, SourceType.RADIO_ES).forEach { type ->
            assertTrue(
                shouldRenderUnboundedBrowse(type),
                "$type is an unbounded catalogue with no library_items mirror — " +
                    "the host must render the browse screen, not the empty library screen",
            )
        }
    }

    @Test
    fun keepsTheLibraryScreenForRoomMirroredSources() {
        listOf(SourceType.ABS, SourceType.KOMGA, SourceType.LOCAL_FILES).forEach { type ->
            assertFalse(
                shouldRenderUnboundedBrowse(type),
                "$type mirrors its items into Room, so it must keep LibraryItemsScreen",
            )
        }
    }

    @Test
    fun fallsBackToTheLibraryScreenBeforeTheActiveSourceResolves() {
        // Cold start: the drawer has not reported an active source yet. Android's
        // libraryEntryRoute falls back to `library_items` for a null sourceType and corrects on
        // the next selection; do the same rather than crashing the browse screen's VM lookup.
        assertFalse(shouldRenderUnboundedBrowse(null))
    }

    @Test
    fun doesNotRouteOReillyWhichHasNoIosLogin() {
        // O'Reilly is `isUnboundedCatalog` but authenticates through a WebView login iOS does not
        // implement, so `unboundedBrowseViewModel` has no branch for it and would error. Keeping
        // it out of the routing set is what makes that unreachable rather than a latent crash.
        assertFalse(shouldRenderUnboundedBrowse(SourceType.OREILLY))
    }

    @Test
    fun audioRootDrivesSquareCoversPerSource() {
        // radio.es is audio throughout; Chitanka's second root is gramofonche; Gutenberg never is.
        assertTrue(isAudioRoot(SourceType.RADIO_ES, "podcasts"))
        assertTrue(isAudioRoot(SourceType.CHITANKA, ChitankaCatalog.ROOT_AUDIOBOOKS))
        assertFalse(isAudioRoot(SourceType.CHITANKA, ChitankaCatalog.ROOT_BOOKS))
        assertFalse(isAudioRoot(SourceType.GUTENBERG, "books"))
    }

    /**
     * The Home, To Read and Annotations local tabs map to their counterparts in LibraryTabContent.
     *
     * UnboundedBrowseScreen uses its own 4-slot tab bar (Home / To Read / Annotations / Library)
     * while LibraryTabContent uses ABS's wider index space. The mapping must be correct or each tab
     * would render the wrong content. Reverting [unboundedLocalTabToLibraryTabIndex] to an identity
     * mapping would silently route To Read to Home, and Annotations to Series.
     */
    @Test
    fun localTabIndexMapsToLibraryTabIndex() {
        assertEquals(0, unboundedLocalTabToLibraryTabIndex(TAB_HOME), "Home tab must map to library tab 0")
        assertEquals(1, unboundedLocalTabToLibraryTabIndex(TAB_TO_READ), "To Read tab must map to library tab 1")
        assertEquals(tabIndexForAnnotations(), unboundedLocalTabToLibraryTabIndex(TAB_ANNOTATIONS), "Annotations tab must map to the shared annotations tab index")
    }

    @Test
    fun everyBrowsableTypeIsAlsoOfferedByThePicker() {
        // Offering a type the browse screen cannot drive would crash; browsing a type the picker
        // never installs would be dead code. Pin them equal apart from the credentialed sources.
        val browsable = unboundedBrowseSourceTypes()
        assertEquals(
            browsable,
            iosSupportedSourceTypes().filter { it.isUnboundedCatalog }.toSet(),
        )
    }
}
