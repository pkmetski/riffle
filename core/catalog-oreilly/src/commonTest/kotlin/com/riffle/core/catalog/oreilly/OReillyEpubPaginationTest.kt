package com.riffle.core.catalog.oreilly

import com.riffle.core.catalog.LazyPublicationShape
import com.riffle.core.catalog.LazySpineItem
import kotlin.test.Test
import kotlin.test.assertFalse

class OReillyEpubPaginationTest {

    // Regression: O'Reilly's CSS files are authored for their scrollable web reader and override
    // the height/overflow properties that Readium's multicol pagination depends on. When injected,
    // scrollHeight > innerHeight persists after Readium applies multicol — the column grid never
    // forms and the chapter renders as a single tall scrollable page, producing vertical scrolling
    // in paginated mode. buildChapterXhtml must produce no stylesheet <link> tags.
    @Test
    fun buildChapterXhtmlOmitsStylesheetLinksForReadiumCompatibility() {
        val pub = LazyPublicationShape(
            bookId = "test-book",
            identifier = "urn:isbn:test",
            title = "Test Book",
            language = "en",
            spine = listOf(LazySpineItem(0, "xhtml/ch1.xhtml", "Chapter 1", 1000L, "application/xhtml+xml")),
            absoluteFilesPrefix = "https://cdn.oreilly.com/books/test/",
            pathFilesPrefix = "/api/v2/epubs/test/",
            cssFullPaths = listOf("styles/main.css", "styles/book.css"),
        )
        val item = pub.spine.first()
        val rawHtml = "<html><body><p>Content</p></body></html>"

        val xhtml = OReillyEpub.buildChapterXhtml(pub, item, rawHtml)

        assertFalse(
            xhtml.contains("<link rel=\"stylesheet\""),
            "buildChapterXhtml must not inject O'Reilly's stylesheet <link> tags: " +
                "they override Readium's multicol height/overflow CSS and cause vertical scrolling in paginated mode",
        )
    }
}
