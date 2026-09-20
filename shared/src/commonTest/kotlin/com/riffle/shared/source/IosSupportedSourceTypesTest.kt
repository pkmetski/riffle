package com.riffle.shared.source

import com.riffle.core.domain.WebSourceDescriptors
import com.riffle.core.models.SourceType
import kotlin.test.Test
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
    fun offersEveryZeroConfigSingletonCatalogue() {
        // Chitanka / Gutenberg / radio.es need no credentials and install straight from the
        // picker via the commonMain SingletonWebSourceInstaller, so none of them may be gated.
        val expected = WebSourceDescriptors.all
            .filter { it.isSingleton && !it.hasCredentials }
            .map { it.type }
        assertTrue(expected.isNotEmpty(), "expected at least one credential-less singleton source")
        assertTrue(iosSupportedSourceTypes().containsAll(expected), "got ${iosSupportedSourceTypes()}")
    }

    @Test
    fun offersKomga() {
        // KomgaSourceAdapter is now in core/sources commonMain (JVM-only imports replaced with
        // multiplatform equivalents). The card must be enabled so iOS users can add a Komga server.
        assertTrue(SourceType.KOMGA in iosSupportedSourceTypes(), "iOS must offer adding a Komga server")
    }
}
