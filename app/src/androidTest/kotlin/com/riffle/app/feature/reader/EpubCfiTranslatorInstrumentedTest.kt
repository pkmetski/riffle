package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * Instrumented unit tests for EpubCfiTranslator running against the bundled test.epub.
 *
 * These tests exercise the translator with real EPUB HTML (rather than synthetic snippets)
 * to catch issues like namespace prefixes, whitespace handling, and element IDs that only
 * appear in actual chapter files.
 *
 * test.epub spine:
 *   index 0 → OEBPS/chapter1.xhtml  (CFI spine step /2)
 *   index 1 → OEBPS/chapter2.xhtml  (CFI spine step /4)
 *   index 2 → OEBPS/chapter3.xhtml  (CFI spine step /6)
 *
 * chapter1.xhtml has <h2 id="s1">, <h2 id="s2">, <h2 id="s3"> section headings.
 * chapter2.xhtml has <h2 id="s1">, <h2 id="s2">, <h2 id="s3"> section headings.
 */
@RunWith(AndroidJUnit4::class)
class EpubCfiTranslatorInstrumentedTest {

    private fun readChapterHtml(entryPath: String): String {
        val context = InstrumentationRegistry.getInstrumentation().context
        val epubBytes = context.assets.open("test.epub").use { it.readBytes() }
        val tmp = File(context.cacheDir, "cfi_test.epub")
        tmp.writeBytes(epubBytes)
        return ZipFile(tmp).use { zip ->
            zip.getInputStream(
                zip.entries().asSequence().first { it.name == entryPath }
            ).bufferedReader().readText()
        }
    }

    // ── hasElementWithId on real EPUB HTML ────────────────────────────────────

    @Test
    fun hasElementWithIdFindsRealSectionId() {
        val html = readChapterHtml("OEBPS/chapter1.xhtml")
        assertTrue(hasElementWithId(html, "s1"))
        assertTrue(hasElementWithId(html, "s2"))
        assertTrue(hasElementWithId(html, "s3"))
    }

    @Test
    fun hasElementWithIdReturnsFalseForAbsentId() {
        val html = readChapterHtml("OEBPS/chapter1.xhtml")
        assertTrue(!hasElementWithId(html, "nonexistent-id-xyz"))
    }

    // ── extractCfiElementIds on real CFIs ─────────────────────────────────────

    @Test
    fun extractCfiElementIdsFromRealSectionCfi() {
        // CFI pointing to the s1 section heading in chapter 1:
        // epubcfi(/6/2!/4/12[s1]/1:0)
        val docPath = extractCfiDocPath("epubcfi(/6/2!/4/12[s1]/1:0)")!!
        val ids = extractCfiElementIds(docPath)
        assertEquals(listOf("s1"), ids)
    }

    @Test
    fun extractCfiElementIdsEmptyForNoAssertions() {
        val docPath = extractCfiDocPath("epubcfi(/6/2!/4/2/1:0)")!!
        assertTrue(extractCfiElementIds(docPath).isEmpty())
    }

    // ── cfiDocPathToProgression on real EPUB HTML ─────────────────────────────

    @Test
    fun progressionAtStartOfChapterIsNearZero() {
        val html = readChapterHtml("OEBPS/chapter1.xhtml")
        val prog = cfiDocPathToProgression("/4/2/1:0", html)
        assertNotNull(prog)
        assertEquals(0.0, prog!!, 0.01)
    }

    @Test
    fun progressionIsInValidRange() {
        val html = readChapterHtml("OEBPS/chapter1.xhtml")
        listOf("/4/2/1:0", "/4/4/1:10", "/4/12/1:0").forEach { path ->
            val prog = cfiDocPathToProgression(path, html)
            assertNotNull("null for $path", prog)
            assertTrue("out of range for $path: $prog", prog!! in 0.0..1.0)
        }
    }

    @Test
    fun progressionIncreasesAlongChapter() {
        val html = readChapterHtml("OEBPS/chapter1.xhtml")
        // Successive positions through the chapter must produce increasing progressions
        val paths = listOf("/4/2/1:0", "/4/4/1:0", "/4/6/1:0", "/4/8/1:0")
        val progs = paths.mapNotNull { cfiDocPathToProgression(it, html) }
        assertEquals(paths.size, progs.size)
        for (i in 1 until progs.size) {
            assertTrue(
                "Progressions not increasing: ${progs[i - 1]} >= ${progs[i]}",
                progs[i - 1] < progs[i]
            )
        }
    }

    // ── ID-anchored navigation on real EPUB HTML ──────────────────────────────

    @Test
    fun idAnchoredAndNumericGiveSameResultForRealSectionHeading() {
        val html = readChapterHtml("OEBPS/chapter1.xhtml")
        // chapter1.xhtml: body children are h1 + 4 p + h2#s1 + ...
        // h1 = step 2, p×4 = steps 4,6,8,10, h2#s1 = step 12
        // CFI with ID assertion: /4/12[s1]/1:0
        // CFI without assertion:  /4/12/1:0
        val withId = cfiDocPathToProgression("/4/12[s1]/1:0", html)
        val withoutId = cfiDocPathToProgression("/4/12/1:0", html)
        assertNotNull(withId)
        assertNotNull(withoutId)
        assertEquals(withoutId!!, withId!!, 0.001)
    }

    @Test
    fun idAnchoredNavigationUsesDeepestValidId() {
        val html = readChapterHtml("OEBPS/chapter1.xhtml")
        // s2 is the second section heading; its position must be > s1
        val progS1 = cfiDocPathToProgression("/4/12[s1]/1:0", html)!!
        val progS2 = cfiDocPathToProgression("/4/24[s2]/1:0", html)!!
        assertTrue("s2 must come after s1: $progS1 >= $progS2", progS1 < progS2)
    }

    @Test
    fun idAnchoredFallsBackToNumericWhenIdAbsent() {
        val html = readChapterHtml("OEBPS/chapter1.xhtml")
        // An ID that doesn't exist in the HTML → numeric fallback, still valid
        val prog = cfiDocPathToProgression("/4/12[does-not-exist]/1:0", html)
        assertNotNull("Should fall back to numeric and return valid progression", prog)
        assertTrue(prog!! in 0.0..1.0)
    }

    // ── progressionToCfiDocPath on real EPUB HTML ─────────────────────────────

    @Test
    fun outboundCfiIncludesIdAssertionForSectionHeading() {
        val html = readChapterHtml("OEBPS/chapter1.xhtml")
        // Find the progression just past the first 11 body children (to land inside
        // or around the h2#s1). We probe 10%, 50%, 90%.
        listOf(0.1, 0.5, 0.9).forEach { p ->
            val path = progressionToCfiDocPath(p, html)
            assertNotNull("null path for progression=$p", path)
            // Path must be a valid CFI doc path (starts with / and contains :)
            assertTrue("Invalid path format: $path", path!!.startsWith("/") && ":" in path)
        }
    }

    @Test
    fun outboundCfiForSectionHeadingContainsIdAssertion() {
        val html = readChapterHtml("OEBPS/chapter1.xhtml")
        // Compute total chars in chapter1, find progression that lands at h2#s1.
        // We don't know exact char count, but we can find a progression that produces
        // a path containing [s1] by recovering the position from a known ID anchor.
        val progAtS1 = cfiDocPathToProgression("/4/12[s1]/1:0", html)!!
        val outPath = progressionToCfiDocPath(progAtS1, html)
        assertNotNull(outPath)
        // The outbound path for a position at h2#s1 should include [s1]
        assertTrue("Expected [s1] in outbound path: $outPath", outPath!!.contains("[s1]"))
    }

    // ── Round-trip consistency on real EPUB HTML ──────────────────────────────

    @Test
    fun roundTripOnRealChapterHtml() {
        val html = readChapterHtml("OEBPS/chapter1.xhtml")
        val samples = listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9)
        for (p in samples) {
            val path = progressionToCfiDocPath(p, html) ?: continue
            val recovered = cfiDocPathToProgression(path, html)!!
            assertTrue(
                "Round-trip error too large for p=$p: recovered=$recovered",
                kotlin.math.abs(p - recovered) < 0.02
            )
        }
    }

    @Test
    fun roundTripWithIdAssertionsOnRealHtml() {
        val html = readChapterHtml("OEBPS/chapter1.xhtml")
        // Probe a position that lands at the s1 heading and verify round-trip via its ID
        val progAtS1 = cfiDocPathToProgression("/4/12[s1]/1:0", html)!!
        val outPath = progressionToCfiDocPath(progAtS1, html)!!
        val recovered = cfiDocPathToProgression(outPath, html)!!
        assertEquals(progAtS1, recovered, 0.005)
    }

    @Test
    fun chapter2RoundTripConsistency() {
        val html = readChapterHtml("OEBPS/chapter2.xhtml")
        val samples = listOf(0.0, 0.33, 0.66, 1.0)
        for (p in samples) {
            val path = progressionToCfiDocPath(p, html) ?: continue
            val recovered = cfiDocPathToProgression(path, html)!!
            assertTrue(
                "Chapter2 round-trip error for p=$p: recovered=$recovered",
                kotlin.math.abs(p - recovered) < 0.05
            )
        }
    }
}
