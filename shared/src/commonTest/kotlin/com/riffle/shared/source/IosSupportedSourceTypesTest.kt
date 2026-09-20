package com.riffle.shared.source

import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun offersEveryUnboundedCatalogueItCanBrowse() {
        // Chitanka / Gutenberg / radio.es install via the commonMain SingletonWebSourceInstaller.
        // Their contents are network-only (ADR 0051), so nothing lands in Room and
        // IosLibraryRefresherImpl returns Success for them exactly as Android's
        // LibraryRepositoryImpl does — what makes them usable is the browse surface, not a mirror.
        // Offering them while that surface did not exist produced a permanently empty library with
        // no error (#1071 §17); removing them from the picker was the wrong fix, because the
        // Android app browses all three. This asserts the picker offers every unbounded catalogue
        // UnboundedBrowseScreen can drive.
        val supported = iosSupportedSourceTypes()
        val browsable = unboundedBrowseSourceTypes()
        assertTrue(browsable.isNotEmpty(), "expected at least one browsable unbounded-catalog source type")
        browsable.forEach { type ->
            assertTrue(type in supported, "iOS can browse $type, so its card must be installable")
        }
    }

    @Test
    fun browsesEveryUnboundedCatalogueExceptOReilly() {
        // The browse screen resolves its ViewModel by SourceType and errors on an unknown one, so
        // a new `isUnboundedCatalog` type added to SourceType must either get a ViewModel or be
        // excluded here deliberately. O'Reilly is the deliberate exclusion (see below).
        val expected = WebSourceDescriptors.all
            .map { it.type }
            .filter { it.isUnboundedCatalog && it != SourceType.OREILLY }
            .toSet()
        assertEquals(expected, unboundedBrowseSourceTypes())
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
