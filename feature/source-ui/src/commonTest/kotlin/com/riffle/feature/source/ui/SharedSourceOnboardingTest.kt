package com.riffle.feature.source.ui

import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * iOS-executed coverage of the source-onboarding logic this module shares between Android and
 * iOS. Runs on `iosSimulatorArm64Test`.
 *
 * Why this exists next to the exhaustive suites in `src/androidHostTest`: Kotlin/Native rejects
 * backticked test names containing `(`, `)` or `,` ("Name contains illegal characters"), and
 * several of the ported `@Test` names carry those characters. The repo's `checkTestGuardrails`
 * forbids renaming an existing `@Test`, so those suites stay JVM-side with their names intact and
 * this class re-asserts the same shared invariants under Native-legal names, so a regression in
 * the shared code is caught on iOS and not only on Android.
 */
class SharedSourceOnboardingTest {

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

    // ---- picker cards -------------------------------------------------------

    @Test
    fun pickerCardsAreOrderedByDescriptorPickerOrder() {
        val cards = sourceTypeCards()
        assertEquals(6, cards.size)
        assertEquals(
            listOf(
                SourceType.ABS,
                SourceType.LOCAL_FILES,
                SourceType.CHITANKA,
                SourceType.GUTENBERG,
                SourceType.KOMGA,
                SourceType.RADIO_ES,
            ),
            cards.map { it.type },
        )
    }

    @Test
    fun singletonCardsAreHiddenOnceInstalled() {
        val cards = sourceTypeCards(
            installedTypes = setOf(
                SourceType.LOCAL_FILES,
                SourceType.CHITANKA,
                SourceType.GUTENBERG,
                SourceType.RADIO_ES,
            ),
        )
        assertEquals(listOf(SourceType.ABS, SourceType.KOMGA), cards.map { it.type })
    }

    @Test
    fun absCardStaysVisibleWithAnAbsSourceInstalledBecauseItIsMultiServer() {
        val cards = sourceTypeCards(installedTypes = setOf(SourceType.ABS))
        assertTrue(cards.any { it.type == SourceType.ABS })
    }

    @Test
    fun everyPickerCardCarriesATitleAndBlurbResource() {
        sourceTypeCards().forEach { card ->
            assertEquals(sourceDisplayNameRes(card.type), card.titleRes)
            assertEquals(sourcePickerBlurbRes(card.type), card.subtitleRes)
        }
    }

    // ---- icon resolution ----------------------------------------------------

    @Test
    fun everySourceTypeResolvesToItsOwnBundledMonogram() {
        val byType = SourceType.values().associateWith { SourceIconResolver.fallbackDrawableFor(it) }
        assertEquals(SourceType.values().size, byType.values.distinct().size)
        assertTrue(
            SourceIconResolver.fallbackDrawableFor(SourceType.ABS, ServerType.AUDIOBOOKSHELF) !=
                SourceIconResolver.fallbackDrawableFor(SourceType.ABS, ServerType.STORYTELLER_SERVICE),
        )
    }

    @Test
    fun faviconUrlForAudiobookshelfUsesTheServerBaseUrl() {
        assertEquals(
            "https://abs.example.com/Logo.png",
            SourceIconResolver.faviconUrlFor(source(SourceType.ABS, url = "https://abs.example.com")),
        )
    }

    @Test
    fun faviconUrlForStorytellerUsesTheAppleTouchIcon() {
        assertEquals(
            "https://story.example.com/apple-touch-icon.png",
            SourceIconResolver.faviconUrlFor(
                source(SourceType.ABS, ServerType.STORYTELLER_SERVICE, "https://story.example.com"),
            ),
        )
    }

    @Test
    fun faviconUrlIsNullForSourcesWithoutABrandedFavicon() {
        assertNull(SourceIconResolver.faviconUrlFor(source(SourceType.CHITANKA)))
        assertNull(SourceIconResolver.faviconUrlFor(source(SourceType.GUTENBERG)))
        assertNull(SourceIconResolver.faviconUrlFor(source(SourceType.LOCAL_FILES)))
    }

    @Test
    fun typeOnlyFaviconLookupOnlyResolvesFixedCdnUrls() {
        assertEquals(
            "https://www.radio.es/assets/fav/favicon-48x48.png",
            SourceIconResolver.faviconUrlFor(SourceType.RADIO_ES),
        )
        assertNull(SourceIconResolver.faviconUrlFor(SourceType.ABS))
        assertNull(SourceIconResolver.faviconUrlFor(SourceType.KOMGA))
    }

    // ---- add-source form copy ----------------------------------------------

    @Test
    fun komgaAddSourceFormResolvesThroughResources() {
        val form = addSourceFormResources(SourceType.KOMGA, ServerType.AUDIOBOOKSHELF)
        assertTrue(form != null)
        // Local files has a subtitle but no credentialed form — it is not a login-backed source.
        assertTrue(sourceSubtitleRes(SourceType.LOCAL_FILES) != null)
        assertNull(addSourceFormResources(SourceType.LOCAL_FILES, ServerType.AUDIOBOOKSHELF))
    }

    @Test
    fun absFormCopyDiffersBetweenAudiobookshelfAndStoryteller() {
        val abs = addSourceFormResources(SourceType.ABS, ServerType.AUDIOBOOKSHELF)!!
        val storyteller = addSourceFormResources(SourceType.ABS, ServerType.STORYTELLER_SERVICE)!!
        assertTrue(abs.addTitle != storyteller.addTitle)
        assertTrue(abs.removeLabel != storyteller.removeLabel)
        assertEquals(abs.urlLabel, storyteller.urlLabel)
    }

    // ---- add-source backend routing ----------------------------------------

    @Test
    fun credentialedBackendRouteTypesStayStable() {
        assertEquals("audiobookshelf", AddSourceBackend.Audiobookshelf.routeType)
        assertEquals("storyteller", AddSourceBackend.Storyteller.routeType)
        assertEquals("webdav", AddSourceBackend.Webdav.routeType)
        assertEquals(
            "komga",
            AddSourceBackend.Credentialed(SourceType.KOMGA, ServerType.AUDIOBOOKSHELF).routeType,
        )
    }

    // ---- auth header --------------------------------------------------------

    @Test
    fun authHeaderWrapsBareTokensAndPassesSchemesThrough() {
        assertEquals("Bearer abc123", "abc123".asAuthHeader())
        assertEquals("Bearer abc123", "Bearer abc123".asAuthHeader())
        assertEquals("Basic dGVzdDp0ZXN0", "Basic dGVzdDp0ZXN0".asAuthHeader())
        assertEquals("Digest xyz", "Digest xyz".asAuthHeader())
        assertEquals("", "".asAuthHeader())
    }

    // ---- WebDAV host extraction --------------------------------------------

    @Test
    fun webdavHostStripsSchemePathPortAndUserinfo() {
        assertEquals("dav.example.com", webdavHostOf("https://dav.example.com/store"))
        assertEquals("dav.example.com", webdavHostOf("http://dav.example.com:8080/store?a=1"))
        assertEquals("dav.example.com", webdavHostOf("https://user:pw@dav.example.com/store"))
        assertEquals("[::1]", webdavHostOf("http://[::1]:8080/dav"))
    }

    @Test
    fun webdavHostFallsBackToTheWholeValueWhenThereIsNoAuthority() {
        // java.net.URI(...).host was null here and the banner fell back to the raw baseUrl.
        assertEquals("dav.example.com/store", webdavHostOf("dav.example.com/store"))
        assertEquals("", webdavHostOf(""))
    }
}
