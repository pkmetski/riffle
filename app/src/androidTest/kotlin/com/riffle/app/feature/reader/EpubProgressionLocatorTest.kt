package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.cfiDocPathToProgression
import com.riffle.core.domain.extractCfiDocPath
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import java.io.File
import javax.inject.Inject

/**
 * Validates `Publication.locateProgression(totalProgression)` — the **fallback** inbound-sync
 * path used when the server's `ebookLocation` CFI is absent or unparseable.
 *
 * The primary inbound path (ADR-0013) is: server CFI → EpubCfiTranslator
 * (extractCfiDocPath + cfiDocPathToProgression) → within-chapter progression → Locator.
 * That path is covered by CfiSyncContractTest and EpubCfiTranslatorInstrumentedTest.
 *
 * This class guards the fallback: when inbound CFI translation fails (empty ebookLocation,
 * malformed CFI, unrecognised spine index), the ViewModel falls back to
 * `pub.locateProgression(ebookProgress)`. These tests verify the fallback produces
 * Locators that are monotone and carry non-zero within-chapter progression — so a
 * position received as a bare float is still navigable.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class EpubProgressionLocatorTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val tmp = TemporaryFolder()

    @Inject lateinit var assetRetriever: AssetRetriever
    @Inject lateinit var publicationOpener: PublicationOpener

    @Before fun setUp() { hiltRule.inject() }

    @Test
    fun locateProgressionZeroReturnsLocatorAtBookStart() = runTest {
        val pub = openTestEpub()

        val locator = pub.locateProgression(0.0)

        assertNotNull("locateProgression(0.0) must return a Locator", locator)
        assertEquals(
            "totalProgression 0.0 should land in the first spine item",
            pub.readingOrder[0].url().toString(),
            locator!!.href.toString(),
        )
    }

    @Test
    fun locateProgressionOneReturnsLocatorAtLastChapter() = runTest {
        val pub = openTestEpub()

        val locator = pub.locateProgression(1.0)

        assertNotNull(locator)
        val lastSpineHref = pub.readingOrder.last().url().toString()
        assertEquals(
            "totalProgression 1.0 should land in the final spine item",
            lastSpineHref,
            locator!!.href.toString(),
        )
    }

    @Test
    fun locateProgressionMidBookProducesNonZeroWithinChapterProgression() = runTest {
        val pub = openTestEpub()
        // 0.5 must produce a Locator with `locations.progression > 0` — otherwise inbound
        // sync would silently land at the START of whichever chapter contains 0.5, which
        // is the original bug we're fixing. Whether the resolved chapter is the first or
        // a later one depends on how content is distributed in the test EPUB, but the
        // within-chapter progression must be non-trivial.
        val locator = pub.locateProgression(0.5)

        assertNotNull(locator)
        val withinChapter = locator!!.locations.progression
        assertNotNull("Locator must carry within-chapter progression", withinChapter)
        assertTrue(
            "0.5 totalProgression must yield a non-zero within-chapter progression; " +
                "got ${withinChapter} in ${locator.href}",
            withinChapter!! > 0.0,
        )
    }

    @Test
    fun locateProgressionIsMonotonicAcrossSpine() = runTest {
        val pub = openTestEpub()

        // Resolve a sequence of increasing progressions and confirm the spine index never
        // decreases. This guards against locateProgression() returning arbitrary chapters.
        val samples = listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { p -> p to pub.locateProgression(p) }
        val hrefToIndex = pub.readingOrder.mapIndexed { i, link -> link.url().toString() to i }.toMap()

        var lastIndex = -1
        for ((p, loc) in samples) {
            assertNotNull("locateProgression($p) returned null", loc)
            val idx = hrefToIndex[loc!!.href.toString()]
                ?: error("locateProgression($p) href ${loc.href} not in reading order")
            assertTrue(
                "Spine index for $p (=$idx) must not regress below previous ($lastIndex)",
                idx >= lastIndex,
            )
            lastIndex = idx
        }
    }

    @Test
    fun locateProgressionWithinChapterEncodesWithinChapterProgression() = runTest {
        val pub = openTestEpub()
        // Two progressions that land in the same chapter must produce locators with
        // *different* `locations.progression` values — otherwise inbound sync collapses
        // to the chapter start regardless of how far into the chapter the server said.
        val a = pub.locateProgression(0.05)!!
        val b = pub.locateProgression(0.20)!!

        // Both should be in an early chapter; whether or not it's the same chapter depends
        // on the EPUB, but if it IS the same chapter we require the in-chapter progression
        // to differ. If they're in different chapters this test is trivially satisfied.
        if (a.href.toString() == b.href.toString()) {
            val pa = a.locations.progression ?: 0.0
            val pb = b.locations.progression ?: 0.0
            assertTrue(
                "Two distinct totalProgressions inside the same chapter must yield " +
                    "different within-chapter progression. got pa=$pa pb=$pb",
                kotlin.math.abs(pa - pb) > 0.001,
            )
        }
    }

    private suspend fun openTestEpub() = run {
        val context = InstrumentationRegistry.getInstrumentation().context
        val epubBytes = context.assets.open("test.epub").use { it.readBytes() }
        val epubFile = File(tmp.newFolder("epub"), "test.epub").also { it.writeBytes(epubBytes) }
        val url = AbsoluteUrl("file://${epubFile.absolutePath}")!!
        val asset = (assetRetriever.retrieve(url) as Try.Success).value
        (publicationOpener.open(asset, allowUserInteraction = false) as Try.Success).value
    }
}
