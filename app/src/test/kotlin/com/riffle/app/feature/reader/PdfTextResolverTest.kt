package com.riffle.app.feature.reader

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PdfTextResolverTest {

    /**
     * In-memory PdfiumTextSource backed by a single string. Char positions
     * are not geometric — `getCharIndexAtPos` interprets (x,y) as (charIndex,
     * 0) for tests. This is enough to exercise the resolver's pure logic
     * (word boundaries, snippet extraction) without needing a real Pdfium.
     */
    private class FakeSource(val text: String) : PdfiumTextSource {
        override fun countChars(pagePtr: Long): Int = text.length
        override fun getText(pagePtr: Long, startIndex: Int, count: Int): String =
            text.substring(startIndex, (startIndex + count).coerceAtMost(text.length))
        override fun getCharBox(pagePtr: Long, charIndex: Int): RectF? =
            if (charIndex in text.indices) RectF(0f, 0f, 1f, 1f) else null
        override fun getCharIndexAtPos(
            pagePtr: Long, x: Double, y: Double, tolX: Double, tolY: Double,
        ): Int {
            val idx = x.toInt()
            return if (idx in text.indices) idx else -1
        }
        override fun rectsForRange(pagePtr: Long, startIndex: Int, count: Int): List<RectF> =
            if (count <= 0) emptyList() else listOf(RectF(startIndex.toFloat(), 0f, (startIndex + count).toFloat(), 1f))
    }

    private fun resolverFor(text: String) = PdfTextResolver(FakeSource(text))

    @Test
    fun `wordAtPoint returns the word containing the hit char`() {
        val resolver = resolverFor("The quick brown fox")
        // 'q' is at index 4
        val r = resolver.wordAtPoint(0L, x = 4.0, y = 0.0)!!
        assertEquals(CharIndex(4), r.start)
        assertEquals(CharIndex(9), r.endExclusive)  // "quick"
        assertEquals("quick", "The quick brown fox".substring(4, 9))
    }

    @Test
    fun `wordAtPoint at start of page returns the leading word`() {
        val resolver = resolverFor("Hello world")
        val r = resolver.wordAtPoint(0L, x = 0.0, y = 0.0)!!
        assertEquals(CharIndex(0), r.start)
        assertEquals(CharIndex(5), r.endExclusive)
    }

    @Test
    fun `wordAtPoint at end of page returns the trailing word`() {
        val resolver = resolverFor("Hello world")
        val r = resolver.wordAtPoint(0L, x = 10.0, y = 0.0)!!
        assertEquals(CharIndex(6), r.start)
        assertEquals(CharIndex(11), r.endExclusive)
    }

    @Test
    fun `wordAtPoint on whitespace returns null`() {
        val resolver = resolverFor("Hello world")
        // space is at index 5
        assertNull(resolver.wordAtPoint(0L, x = 5.0, y = 0.0))
    }

    @Test
    fun `wordAtPoint outside any char returns null`() {
        val resolver = resolverFor("Hello")
        assertNull(resolver.wordAtPoint(0L, x = 99.0, y = 0.0))
    }

    @Test
    fun `resolveCharAt returns the char index when hit`() {
        val resolver = resolverFor("Hello world")
        assertEquals(CharIndex(3), resolver.resolveCharAt(0L, x = 3.0, y = 0.0))
    }

    @Test
    fun `resolveCharAt outside any char returns null`() {
        val resolver = resolverFor("Hello")
        assertNull(resolver.resolveCharAt(0L, x = 99.0, y = 0.0))
    }

    @Test
    fun `quadsForRange returns rectangles for the range`() {
        val resolver = resolverFor("Hello world")
        val quads = resolver.quadsForRange(0L, CharRange(CharIndex(6), CharIndex(11)))
        // android.graphics.RectF is stubbed-to-zero in unit-test JVM (no
        // Robolectric here), so we can't assert coordinates. The resolver's
        // contract is "wrap source.rectsForRange" — verifying the count + the
        // arguments threaded through is the JVM-testable surface; coord
        // round-trips are covered by the core/pdfium-text instrumentation
        // smoke tests on a real device.
        assertEquals(1, quads.size)
    }

    @Test
    fun `quadsForRange returns empty for empty range`() {
        val resolver = resolverFor("Hello")
        assertEquals(0, resolver.quadsForRange(0L, CharRange(CharIndex(2), CharIndex(2))).size)
    }

    @Test
    fun `extractSnippet pulls highlight + leading + trailing context`() {
        val text = "lorem ipsum dolor sit amet consectetur adipiscing elit"
        val resolver = resolverFor(text)
        // Range covers "dolor" at indices 12-17
        val snippet = resolver.extractSnippet(
            pagePtr = 0L,
            range = CharRange(CharIndex(12), CharIndex(17)),
            contextChars = 10,
        )
        assertEquals("dolor", snippet.highlight)
        assertEquals("rem ipsum ", snippet.before)
        assertEquals(" sit amet ", snippet.after)
    }

    @Test
    fun `extractSnippet clamps context at start of page`() {
        val text = "abc def ghi"
        val resolver = resolverFor(text)
        val snippet = resolver.extractSnippet(
            pagePtr = 0L,
            range = CharRange(CharIndex(0), CharIndex(3)),
            contextChars = 32,
        )
        assertEquals("abc", snippet.highlight)
        assertEquals("", snippet.before)
        assertEquals(" def ghi", snippet.after)
    }

    @Test
    fun `extractSnippet clamps context at end of page`() {
        val text = "abc def ghi"
        val resolver = resolverFor(text)
        val snippet = resolver.extractSnippet(
            pagePtr = 0L,
            range = CharRange(CharIndex(8), CharIndex(11)),
            contextChars = 32,
        )
        assertEquals("ghi", snippet.highlight)
        assertEquals("abc def ", snippet.before)
        assertEquals("", snippet.after)
    }

    @Test
    fun `extractSnippet with default 32-char context matches EPUB shape`() {
        val text = "a".repeat(40) + "TARGET" + "b".repeat(40)
        val resolver = resolverFor(text)
        val snippet = resolver.extractSnippet(
            pagePtr = 0L,
            range = CharRange(CharIndex(40), CharIndex(46)),
        )
        assertEquals("TARGET", snippet.highlight)
        assertEquals("a".repeat(32), snippet.before)
        assertEquals("b".repeat(32), snippet.after)
    }

    @Test
    fun `extractSnippet on empty range returns empty triple`() {
        val resolver = resolverFor("anything")
        val snippet = resolver.extractSnippet(
            pagePtr = 0L,
            range = CharRange(CharIndex(3), CharIndex(3)),
        )
        assertEquals("", snippet.highlight)
        assertEquals("", snippet.before)
        assertEquals("", snippet.after)
    }
}
