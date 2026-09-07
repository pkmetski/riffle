package com.riffle.feature.source.ui

import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ic_source_audiobookshelf
import com.riffle.feature.source.ui.generated.resources.ic_source_chitanka
import com.riffle.feature.source.ui.generated.resources.ic_source_gutenberg
import com.riffle.feature.source.ui.generated.resources.ic_source_local_files
import com.riffle.feature.source.ui.generated.resources.ic_source_radio_es
import com.riffle.feature.source.ui.generated.resources.ic_source_storyteller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the wiring between Source type/serverType and the favicon URL + fallback drawable used
 * by the source switcher + add-source picker. A regression on any of these branches would flip
 * the icon back to the placeholder-only state that shipped before this feature.
 */
class SourceIconResolverTest {

    private fun source(
        type: SourceType,
        serverType: ServerType = ServerType.AUDIOBOOKSHELF,
        url: String = "https://example.com",
    ): Source = Source(
        id = "id",
        url = SourceUrl.parse(url) ?: error("invalid test URL: $url"),
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = type,
        serverType = serverType,
    )

    // ---- faviconUrlFor ------------------------------------------------------

    @Test
    fun `favicon URL for Audiobookshelf uses Logo dot png at the server base URL`() {
        val url = SourceIconResolver.faviconUrlFor(
            source(type = SourceType.ABS, serverType = ServerType.AUDIOBOOKSHELF, url = "https://abs.example.com")
        )
        assertEquals("https://abs.example.com/Logo.png", url)
    }

    @Test
    fun `favicon URL for Storyteller uses apple-touch-icon dot png at the service base URL`() {
        val url = SourceIconResolver.faviconUrlFor(
            source(
                type = SourceType.ABS,
                serverType = ServerType.STORYTELLER_SERVICE,
                url = "https://storyteller.example.com",
            )
        )
        assertEquals("https://storyteller.example.com/apple-touch-icon.png", url)
    }

    @Test
    fun `favicon URL for Chitanka is null so Coil never attempts to decode the ICO`() {
        val url = SourceIconResolver.faviconUrlFor(source(type = SourceType.CHITANKA))
        assertNull(url)
    }

    @Test
    fun `favicon URL for LocalFiles is null - no network origin`() {
        val url = SourceIconResolver.faviconUrlFor(
            source(type = SourceType.LOCAL_FILES, url = "https://localfiles.invalid"),
        )
        assertNull(url)
    }

    @Test
    fun `favicon URL for Gutenberg is null - gutendex is an API mirror, no branded favicon`() {
        val url = SourceIconResolver.faviconUrlFor(source(type = SourceType.GUTENBERG))
        assertNull(url)
    }

    @Test
    fun `favicon URL preserves the trailing-slash-stripping done by SourceUrl_parse`() {
        // SourceUrl.parse strips one trailing slash; the favicon URL must therefore not have a
        // double slash before the path segment.
        val url = SourceIconResolver.faviconUrlFor(
            source(type = SourceType.ABS, url = "https://abs.example.com/"),
        )
        assertEquals("https://abs.example.com/Logo.png", url)
    }

    // ---- fallbackDrawableFor (by Source) ------------------------------------

    @Test
    fun `fallback drawable for Audiobookshelf source is the ABS monogram`() {
        val res = SourceIconResolver.fallbackDrawableFor(
            source(type = SourceType.ABS, serverType = ServerType.AUDIOBOOKSHELF),
        )
        assertEquals(Res.drawable.ic_source_audiobookshelf, res)
    }

    @Test
    fun `fallback drawable for Storyteller source is the Storyteller monogram`() {
        val res = SourceIconResolver.fallbackDrawableFor(
            source(type = SourceType.ABS, serverType = ServerType.STORYTELLER_SERVICE),
        )
        assertEquals(Res.drawable.ic_source_storyteller, res)
    }

    @Test
    fun `fallback drawable for Chitanka source is the Chitanka monogram`() {
        val res = SourceIconResolver.fallbackDrawableFor(source(type = SourceType.CHITANKA))
        assertEquals(Res.drawable.ic_source_chitanka, res)
    }

    @Test
    fun `fallback drawable for LocalFiles source is the LocalFiles monogram`() {
        val res = SourceIconResolver.fallbackDrawableFor(source(type = SourceType.LOCAL_FILES))
        assertEquals(Res.drawable.ic_source_local_files, res)
    }

    @Test
    fun `fallback drawable for Gutenberg source is the Gutenberg monogram`() {
        val res = SourceIconResolver.fallbackDrawableFor(source(type = SourceType.GUTENBERG))
        assertEquals(Res.drawable.ic_source_gutenberg, res)
    }

    /**
     * Compile-time exhaustiveness already ensures each [SourceType] has a `when` branch.
     *
     * The original Android assertion was `assertNotEquals(0, res)` — an `R.drawable` reference
     * that did not exist resolved to `0`. Compose Multiplatform resources have no such sentinel:
     * a drawable that is not present in `composeResources/drawable` has no generated `Res.drawable`
     * accessor at all, so the missing-artwork case is now a *compile* error and can never reach
     * this test. What survives as a runtime claim is the failure mode that stayed possible — a new
     * SourceType branch pasted from an existing one, silently reusing another source's monogram —
     * so this asserts every SourceType resolves to its own distinct drawable.
     */
    @Test
    fun `every SourceType resolves to a non-zero bundled drawable`() {
        val byType = SourceType.values().associateWith { SourceIconResolver.fallbackDrawableFor(it) }
        assertEquals(
            SourceType.values().size,
            byType.values.distinct().size,
            "every SourceType must map to its OWN bundled drawable — see SourceIconResolver.fallbackDrawableFor, got $byType",
        )
        // ABS's two ServerType flavours must not collapse onto one monogram either.
        assertTrue(
            SourceIconResolver.fallbackDrawableFor(SourceType.ABS, ServerType.AUDIOBOOKSHELF) !=
                SourceIconResolver.fallbackDrawableFor(SourceType.ABS, ServerType.STORYTELLER_SERVICE),
            "Audiobookshelf and Storyteller must keep distinct monograms",
        )
    }

    // ---- fallbackDrawableFor (by type + serverType) -------------------------

    @Test
    fun `type-only lookup returns ABS monogram for ABS+Audiobookshelf`() {
        val res = SourceIconResolver.fallbackDrawableFor(SourceType.ABS, ServerType.AUDIOBOOKSHELF)
        assertEquals(Res.drawable.ic_source_audiobookshelf, res)
    }

    @Test
    fun `type-only lookup returns Storyteller monogram for ABS+Storyteller`() {
        val res = SourceIconResolver.fallbackDrawableFor(SourceType.ABS, ServerType.STORYTELLER_SERVICE)
        assertEquals(Res.drawable.ic_source_storyteller, res)
    }

    @Test
    fun `type-only lookup returns Chitanka monogram for CHITANKA regardless of serverType`() {
        val res = SourceIconResolver.fallbackDrawableFor(SourceType.CHITANKA)
        assertEquals(Res.drawable.ic_source_chitanka, res)
    }

    @Test
    fun `type-only lookup returns Gutenberg monogram for GUTENBERG regardless of serverType`() {
        val res = SourceIconResolver.fallbackDrawableFor(SourceType.GUTENBERG)
        assertEquals(Res.drawable.ic_source_gutenberg, res)
    }

    @Test
    fun `type-only lookup returns LocalFiles monogram for LOCAL_FILES regardless of serverType`() {
        val res = SourceIconResolver.fallbackDrawableFor(SourceType.LOCAL_FILES)
        assertEquals(Res.drawable.ic_source_local_files, res)
    }

    @Test
    fun `favicon URL for radio_es is the favicon png from the radio es CDN`() {
        val url = SourceIconResolver.faviconUrlFor(
            source(type = SourceType.RADIO_ES, url = "https://radio-es.invalid"),
        )
        assertEquals("https://www.radio.es/assets/fav/favicon-48x48.png", url)
    }

    @Test
    fun `fallback drawable for radio_es source is the radio_es monogram`() {
        val res = SourceIconResolver.fallbackDrawableFor(source(type = SourceType.RADIO_ES))
        assertEquals(Res.drawable.ic_source_radio_es, res)
    }

    @Test
    fun `type-only lookup returns radio_es monogram for RADIO_ES regardless of serverType`() {
        val res = SourceIconResolver.fallbackDrawableFor(SourceType.RADIO_ES)
        assertEquals(Res.drawable.ic_source_radio_es, res)
    }

    @Test
    fun `type-only faviconUrlFor returns fixed CDN url for RADIO_ES without a server url`() {
        val url = SourceIconResolver.faviconUrlFor(SourceType.RADIO_ES)
        assertEquals("https://www.radio.es/assets/fav/favicon-48x48.png", url)
    }

    @Test
    fun `type-only faviconUrlFor returns null for sources requiring a server url`() {
        assertNull(SourceIconResolver.faviconUrlFor(SourceType.ABS))
        assertNull(SourceIconResolver.faviconUrlFor(SourceType.KOMGA))
        assertNull(SourceIconResolver.faviconUrlFor(SourceType.GUTENBERG))
        assertNull(SourceIconResolver.faviconUrlFor(SourceType.CHITANKA))
    }
}
