package com.riffle.shared.source

import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.SourceType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins which source types the iOS Add-Source picker will actually let the user install.
 *
 * This replaces `AddSourceOptionsTest`'s guard now that the empty start destination and the
 * Settings "Sources" section render the real shared picker instead of the two-entry
 * `AddSourceOption` skeleton. Regression context is unchanged from that test: PR #908 left iOS
 * with no way to add an Audiobookshelf server at all, so "ABS and Local Files are offered" is the
 * invariant that must not silently disappear again.
 */
class IosSupportedSourceTypesTest {

    @Test
    fun offersAudiobookshelfAndLocalFiles() {
        val supported = iosSupportedSourceTypes()
        assertTrue(SourceType.ABS in supported, "iOS must offer adding an ABS server")
        assertTrue(SourceType.LOCAL_FILES in supported, "iOS must offer adding local files")
    }

    @Test
    fun offersKomga() {
        // KomgaSourceAdapter is now in core/sources commonMain (JVM-only imports replaced with
        // multiplatform equivalents). The card must be enabled so iOS users can add a Komga server.
        assertTrue(SourceType.KOMGA in iosSupportedSourceTypes(), "iOS must offer adding a Komga server")
    }

    @Test
    fun hidesUnboundedCataloguesIosCannotBrowse() {
        // Chitanka / Gutenberg / radio.es install fine via the commonMain SingletonWebSourceInstaller,
        // which is why they used to be offered. But their contents are network-only (ADR 0051): nothing
        // lands in Room, IosLibraryRefresherImpl returns Success for them, and the only browse surface
        // lives in the Android-only `app` module. Offering them produced a permanently empty library
        // with no error (#1071 §17). Re-admit each type together with the iOS browse surface (#1072).
        val supported = iosSupportedSourceTypes()
        val unbrowsable = WebSourceDescriptors.all
            .map { it.type }
            .filter { it.isUnboundedCatalog }
        assertTrue(unbrowsable.isNotEmpty(), "expected at least one unbounded-catalog source type")
        unbrowsable.forEach { type ->
            assertFalse(type in supported, "iOS must not offer $type until it can browse it")
        }
    }

    @Test
    fun hidesOReillyWhichHasNoIosWebViewLogin() {
        // O'Reilly is a credential-less singleton, so the old "every zero-config singleton" rule let
        // it into this set; it was hidden only by SourceTypePickerScreen's developerModeEnabled
        // default, which SourceOnboardingHost never passes a value for. Wiring that flag would have
        // installed an O'Reilly source with no WebView login and no orm-jwt cookie, whose
        // OReillyCatalogFactory.create returns null forever. Exclude it structurally instead.
        assertFalse(
            SourceType.OREILLY in iosSupportedSourceTypes(),
            "iOS has no O'Reilly WebView login, so its source must not be installable",
        )
    }
}
