package com.riffle.app.feature.reader

import com.riffle.core.domain.epubCfiToSpineIndex
import com.riffle.core.domain.cfiDocPathToProgression
import com.riffle.core.domain.extractCfiDocPath
import com.riffle.core.domain.progressionToCfiDocPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ADR-0013 contract: ebookLocation must pass through EpubCfiTranslator in both directions.
// html: "First paragraph text."(21) + <p id="s2">"Second section here."(20) = 41 chars
class CfiSyncContractTest {

    private val html = """<html><body><p>First paragraph text.</p><p id="s2">Second section here.</p></body></html>"""
    private val totalChars = 41.0

    // ── Outbound path ────────────────────────────────────────────────────────

    @Test
    fun `outbound CFI has epubcfi format with spine step and bang separator`() {
        val spineIndex = 2
        val spineStep = (spineIndex + 1) * 2  // 6
        val docPath = progressionToCfiDocPath(0.5, html)
        assertNotNull(docPath)
        val fullCfi = "epubcfi(/6/$spineStep!$docPath)"
        assertTrue("Must start with epubcfi(/6/", fullCfi.startsWith("epubcfi(/6/"))
        assertTrue("Must contain bang separator", "!" in fullCfi)
        assertTrue("Must end with )", fullCfi.endsWith(")"))
    }

    @Test
    fun `outbound spine step formula is (spineIndex + 1) times 2`() {
        val cases = listOf(0 to 2, 1 to 4, 3 to 8, 79 to 160)
        for ((index, expectedStep) in cases) {
            val computed = (index + 1) * 2
            assertEquals("Spine step for index $index", expectedStep, computed)
            val docPath = progressionToCfiDocPath(0.1, html)!!
            val fullCfi = "epubcfi(/6/$computed!$docPath)"
            assertEquals("epubCfiToSpineIndex round-trips for index $index", index, epubCfiToSpineIndex(fullCfi))
        }
    }

    @Test
    fun `outbound doc path ends with colon-offset character terminus`() {
        val docPath = progressionToCfiDocPath(0.3, html)!!
        assertTrue("Doc path must contain ':' offset terminus: $docPath", ":" in docPath)
        val lastPart = docPath.substringAfterLast('/')
        assertTrue("Last segment must have form N:M: $lastPart", lastPart.matches(Regex("""\d+:\d+""")))
    }

    @Test
    fun `outbound zero progression points to start of chapter`() {
        val docPath = progressionToCfiDocPath(0.0, html)!!
        val recovered = cfiDocPathToProgression(docPath, html)!!
        assertEquals(0.0, recovered, 1.0 / totalChars)
    }

    @Test
    fun `outbound full-book progression points to near end of chapter`() {
        val docPath = progressionToCfiDocPath(1.0, html)!!
        val recovered = cfiDocPathToProgression(docPath, html)!!
        assertTrue("Full-book progression should recover near 1.0, got $recovered", recovered >= 0.9)
    }

    @Test
    fun `outbound embeds element ID assertion for element with id attribute`() {
        // Progression landing at/after "s2" paragraph (21/41 ≈ 0.512) should include [s2]
        val progression = 21.0 / totalChars
        val docPath = progressionToCfiDocPath(progression, html)!!
        assertTrue("Outbound path must embed [s2] for ID-anchored inbound: $docPath", "[s2]" in docPath)
    }

    // ── Inbound path ─────────────────────────────────────────────────────────

    @Test
    fun `inbound extracts correct spine index for standard steps`() {
        assertEquals(0, epubCfiToSpineIndex("epubcfi(/6/2!/4/2/1:0)"))   // step 2 → index 0
        assertEquals(1, epubCfiToSpineIndex("epubcfi(/6/4!/4/2/1:0)"))   // step 4 → index 1
        assertEquals(79, epubCfiToSpineIndex("epubcfi(/6/160!/4/2/1:0)"))// step 160 → index 79
    }

    @Test
    fun `inbound rejects odd spine steps as invalid`() {
        assertNull(epubCfiToSpineIndex("epubcfi(/6/1!/4/2/1:0)"))
        assertNull(epubCfiToSpineIndex("epubcfi(/6/3!/4/2/1:0)"))
        assertNull(epubCfiToSpineIndex("epubcfi(/6/5!/4/2/1:0)"))
    }

    @Test
    fun `inbound returns null for malformed or empty CFI`() {
        assertNull(epubCfiToSpineIndex(""))
        assertNull(epubCfiToSpineIndex("not-a-cfi"))
        assertNull(epubCfiToSpineIndex("/4/2/1:0"))
    }

    @Test
    fun `inbound extracts doc path matching what outbound produced`() {
        val originalDocPath = progressionToCfiDocPath(0.4, html)!!
        val fullCfi = "epubcfi(/6/4!$originalDocPath)"
        assertEquals(originalDocPath, extractCfiDocPath(fullCfi))
    }

    @Test
    fun `inbound converts known doc path to correct within-chapter progression`() {
        // "/4/4[s2]/1:0" points to the start of the second paragraph (21 chars in)
        val docPath = "/4/4[s2]/1:0"
        val progression = cfiDocPathToProgression(docPath, html)!!
        assertEquals(21.0 / totalChars, progression, 0.05)
    }

    @Test
    fun `inbound returns null for empty ebookLocation — 404 fallback signal`() {
        // Source returns ebookLocation="" when book has no progress (404 case).
        // Null here tells the ViewModel to fall through to the ebookProgress float.
        assertNull(extractCfiDocPath(""))
    }

    @Test
    fun `inbound returns null for non-CFI ebookLocation`() {
        assertNull(extractCfiDocPath("not-a-cfi"))
        assertNull(extractCfiDocPath("http://example.com/position"))
        assertNull(extractCfiDocPath("0.42"))
    }

    // ── Full round-trip ───────────────────────────────────────────────────────

    @Test
    fun `outbound then inbound round-trip stays within one-character tolerance`() {
        val tolerance = 1.0 / totalChars
        val samples = listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9)
        for (original in samples) {
            val docPath = progressionToCfiDocPath(original, html) ?: continue
            val fullCfi = "epubcfi(/6/4!$docPath)"

            val spineIndex = epubCfiToSpineIndex(fullCfi)!!
            assertEquals("Spine index must be 1 for step 4", 1, spineIndex)

            val extractedPath = extractCfiDocPath(fullCfi)!!
            val recovered = cfiDocPathToProgression(extractedPath, html)!!
            assertEquals("Round-trip failed for p=$original", original, recovered, tolerance)
        }
    }

    @Test
    fun `round-trip with ID assertion is self-consistent`() {
        val progression = 21.0 / totalChars  // start of "Second section here."
        val docPath = progressionToCfiDocPath(progression, html)!!
        assertTrue("Outbound must embed [s2]: $docPath", "[s2]" in docPath)
        val recovered = cfiDocPathToProgression(docPath, html)!!
        assertEquals(progression, recovered, 1.0 / totalChars)
    }

    @Test
    fun `outbound-inbound preserves monotone ordering`() {
        val progressions = listOf(0.0, 0.2, 0.5, 0.8, 1.0)
        val recovered = progressions.mapNotNull { p ->
            val docPath = progressionToCfiDocPath(p, html) ?: return@mapNotNull null
            val fullCfi = "epubcfi(/6/4!$docPath)"
            cfiDocPathToProgression(extractCfiDocPath(fullCfi)!!, html)
        }
        assertEquals(progressions.size, recovered.size)
        for (i in 1 until recovered.size) {
            assertTrue(
                "Ordering not preserved: ${recovered[i - 1]} > ${recovered[i]}",
                recovered[i - 1] <= recovered[i] + 1.0 / totalChars,
            )
        }
    }

    // ── Contract boundary: 404 / no server progress ───────────────────────────

    @Test
    fun `empty ebookLocation with zero ebookProgress signals no-CFI fallback`() {
        // This is the mapped 404 case: server has no record.
        // Both extractions must return null so the ViewModel uses ebookProgress float.
        assertNull("extractCfiDocPath must be null", extractCfiDocPath(""))
        assertNull("epubCfiToSpineIndex must be null", epubCfiToSpineIndex(""))
    }
}
