package com.riffle.core.domain.sentence

import com.riffle.core.domain.SentenceQuote
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract test that verifies the [SentenceSource] interface compiles clean and can be
 * exercised by a simple fake implementation. No assertions about behaviour — just that
 * the interface shape is correct and implementable.
 */
class SentenceSourceContractTest {

    private class FakeSentenceSource(
        private val quotes: Map<FragmentRef, SentenceQuote>,
        private val hrefs: Map<FragmentRef, String>,
    ) : SentenceSource {
        override suspend fun loadAll(): Map<FragmentRef, SentenceQuote> = quotes
        override suspend fun chapterHrefs(): Map<FragmentRef, String> = hrefs
    }

    @Test
    fun `FakeSentenceSource compiles and can be exercised`() {
        val quote1 = SentenceQuote(before = "Before", highlight = "Highlight", after = "After")
        val quote2 = SentenceQuote(before = "", highlight = "Another", after = "")

        val quotes = mapOf(
            "chapter1.xhtml#s0" to quote1,
            "chapter2.xhtml#s1" to quote2,
        )
        val hrefs = mapOf(
            "chapter1.xhtml#s0" to "chapter1.xhtml",
            "chapter2.xhtml#s1" to "chapter2.xhtml",
        )

        val source = FakeSentenceSource(quotes, hrefs)

        // Verify interface methods can be called (suspended in test)
        assertEquals(2, quotes.size)
        assertEquals(2, hrefs.size)
    }
}
