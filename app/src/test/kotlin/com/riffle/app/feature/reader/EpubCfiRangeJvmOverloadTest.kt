package com.riffle.app.feature.reader

import com.riffle.feature.reader.buildHighlightCfiRangeForSelection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rest of `EpubCfiRangeTest` moved to `feature:reader`'s commonTest (issue #1066) and now runs
 * on iOS too. This case stays behind because it pins the pre-parsed-`Document` overloads, which
 * exist only on the JVM: `EpubReaderViewModel` caches one parsed jsoup document per chapter and
 * reuses it across every annotation in that chapter.
 */
class EpubCfiRangeJvmOverloadTest {

    private val simpleHtml = """
        <html>
          <head><title>Test</title></head>
          <body>
            <p>Hello world</p>
            <p>Second paragraph</p>
          </body>
        </html>
    """.trimIndent()

    @Test
    fun `highlightStartProgression with pre-computed totalChars matches the auto-count overload`() {
        val cfi = buildHighlightCfiRangeForSelection(
            spineStep = 4, html = simpleHtml, startProgression = 0.0, selectedText = "Hello",
        )!!
        val doc = org.jsoup.Jsoup.parse(simpleHtml)
        val totalChars = com.riffle.core.domain.countBodyChars(doc.body())
        val expected = highlightStartProgression(cfi, doc)
        val actual = highlightStartProgression(cfi, doc, totalChars)
        assertEquals(expected, actual)
    }
}
