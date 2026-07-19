package com.riffle.app.ui.source

import com.riffle.app.R
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
        assertEquals(R.drawable.ic_source_audiobookshelf, res)
    }

    @Test
    fun `fallback drawable for Storyteller source is the Storyteller monogram`() {
        val res = SourceIconResolver.fallbackDrawableFor(
            source(type = SourceType.ABS, serverType = ServerType.STORYTELLER_SERVICE),
        )
        assertEquals(R.drawable.ic_source_storyteller, res)
    }

    @Test
    fun `fallback drawable for Chitanka source is the Chitanka monogram`() {
        val res = SourceIconResolver.fallbackDrawableFor(source(type = SourceType.CHITANKA))
        assertEquals(R.drawable.ic_source_chitanka, res)
    }

    @Test
    fun `fallback drawable for LocalFiles source is the LocalFiles monogram`() {
        val res = SourceIconResolver.fallbackDrawableFor(source(type = SourceType.LOCAL_FILES))
        assertEquals(R.drawable.ic_source_local_files, res)
    }

    @Test
    fun `fallback drawable for Gutenberg source is the Gutenberg monogram`() {
        val res = SourceIconResolver.fallbackDrawableFor(source(type = SourceType.GUTENBERG))
        assertEquals(R.drawable.ic_source_gutenberg, res)
    }

    /**
     * Compile-time exhaustiveness already ensures each [SourceType] has a `when` branch, but
     * this runtime check confirms every branch also returns a non-zero (real) drawable id. A
     * new SourceType added without a bundled drawable would produce `0` here and fail this test
     * before reaching a user.
     */
    @Test
    fun `every SourceType resolves to a non-zero bundled drawable`() {
        SourceType.values().forEach { type ->
            val res = SourceIconResolver.fallbackDrawableFor(type)
            assertNotEquals(
                "SourceType.$type must map to a bundled drawable — see SourceIconResolver.fallbackDrawableFor",
                0,
                res,
            )
        }
    }

    // ---- fallbackDrawableFor (by type + serverType) -------------------------

    @Test
    fun `type-only lookup returns ABS monogram for ABS+Audiobookshelf`() {
        val res = SourceIconResolver.fallbackDrawableFor(SourceType.ABS, ServerType.AUDIOBOOKSHELF)
        assertEquals(R.drawable.ic_source_audiobookshelf, res)
    }

    @Test
    fun `type-only lookup returns Storyteller monogram for ABS+Storyteller`() {
        val res = SourceIconResolver.fallbackDrawableFor(SourceType.ABS, ServerType.STORYTELLER_SERVICE)
        assertEquals(R.drawable.ic_source_storyteller, res)
    }

    @Test
    fun `type-only lookup returns Chitanka monogram for CHITANKA regardless of serverType`() {
        val res = SourceIconResolver.fallbackDrawableFor(SourceType.CHITANKA)
        assertEquals(R.drawable.ic_source_chitanka, res)
    }

    @Test
    fun `type-only lookup returns Gutenberg monogram for GUTENBERG regardless of serverType`() {
        val res = SourceIconResolver.fallbackDrawableFor(SourceType.GUTENBERG)
        assertEquals(R.drawable.ic_source_gutenberg, res)
    }

    @Test
    fun `type-only lookup returns LocalFiles monogram for LOCAL_FILES regardless of serverType`() {
        val res = SourceIconResolver.fallbackDrawableFor(SourceType.LOCAL_FILES)
        assertEquals(R.drawable.ic_source_local_files, res)
    }
}
