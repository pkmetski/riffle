package com.riffle.shared.source

import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.SourceType
import com.riffle.feature.source.ui.websource.unboundedBrowseSourceTypes
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun offersOReillyForDeveloperMode() {
        // O'Reilly is gated behind developerModeEnabled in SourceTypePickerScreen
        // (OReillyWebSourceDescriptor.requiresDeveloperMode = true), so it only appears when the
        // user has unlocked developer mode. It must be in the enabled set so the card is active
        // once visible. IosOReillyLoginScreen provides the WKWebView login that harvests orm-jwt.
        assertTrue(
            SourceType.OREILLY in iosSupportedSourceTypes(),
            "iOS O'Reilly WebView login is implemented; its source must be installable in developer mode",
        )
    }

    @Test
    fun everyBrowsableTypeIsAlsoOfferedByThePicker() {
        // Offering a type the browse screen cannot drive would crash; browsing a type the picker
        // never installs would be dead code. Pin them equal, excluding O'Reilly which is an
        // unbounded catalogue that uses a dedicated WebView login flow rather than the generic
        // UnboundedBrowseScreen installation path.
        val browsable = unboundedBrowseSourceTypes()
        assertEquals(
            browsable,
            iosSupportedSourceTypes().filter { it.isUnboundedCatalog && it != SourceType.OREILLY }.toSet(),
        )
    }
}
