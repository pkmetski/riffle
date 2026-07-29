package com.riffle.app.feature.reader.highlights

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.feature.reader.evalSync
import com.riffle.app.feature.reader.withSizedWebViewFixture
import com.riffle.app.feature.reader.withWebViewFixture
import com.riffle.core.database.AnnotationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-WebView coverage for the elided reader's note callout.
 *
 * The synthesised chapter HTML is shared by paginated, vertical, and continuous modes, so loading
 * that production HTML in the API-25 harness WebView verifies the CSS engine used by all three.
 */
@RunWith(AndroidJUnit4::class)
class ElidedNoteCalloutWebViewTest {

    private val factory = HighlightsPublicationFactory()

    @Test
    fun initialNoteIsLabelledAndVisuallyCloserToItsOwnerThanTheNextHighlight() {
        val html = factory.renderChapterHtml(
            ChapterElision(
                href = "chapter.xhtml",
                title = "Chapter",
                highlights = listOf(
                    highlight("owner", "Owning highlight", note = "My note"),
                    highlight("next", "Next highlight"),
                ),
            ),
        )

        withSizedWebViewFixture(html, widthPx = 1080, heightPx = 1600) { webView ->
            assertEquals(
                ELIDED_NOTE_LABEL,
                webView.evalSync(
                    "getComputedStyle(document.querySelector('.riffle-note'),'::before')" +
                        ".content.replace(/[\\\"']/g,'')",
                ).trim('"'),
            )
            assertEquals(
                "note",
                webView.evalSync("document.querySelector('.riffle-note').getAttribute('role')").trim('"'),
            )
            assertEquals(
                ELIDED_NOTE_ARIA_LABEL,
                webView.evalSync(
                    "document.querySelector('.riffle-note').getAttribute('aria-label')",
                ).trim('"'),
            )
            assertEquals(
                "normal",
                webView.evalSync(
                    "getComputedStyle(document.querySelector('.riffle-note')).fontStyle",
                ).trim('"'),
            )
            assertTrue(
                "callout must retain a visible neutral surface on the real WebView",
                webView.evalSync(
                    "getComputedStyle(document.querySelector('.riffle-note')).backgroundColor",
                ).trim('"').startsWith("rgba(127, 127, 127,"),
            )

            val ownerGap = webView.evalSync(
                "(function(){var n=document.querySelector('.riffle-note').getBoundingClientRect();" +
                    "var p=document.querySelector('[data-ann-id=\"owner\"]').closest('p')" +
                    ".getBoundingClientRect();return n.top-p.bottom;})()",
            ).trim('"').toDouble()
            val nextGap = webView.evalSync(
                "(function(){var n=document.querySelector('.riffle-note').getBoundingClientRect();" +
                    "var p=document.querySelector('[data-ann-id=\"next\"]').closest('p')" +
                    ".getBoundingClientRect();return p.top-n.bottom;})()",
            ).trim('"').toDouble()
            assertTrue(
                "note must sit closer to its owner ($ownerGap px) than to the next highlight ($nextGap px)",
                ownerGap < nextGap,
            )
        }
    }

    @Test
    fun liveAddedNoteReceivesTheSameLabelAndSemantics() {
        val html = factory.renderChapterHtml(
            ChapterElision(
                href = "chapter.xhtml",
                title = "Chapter",
                highlights = listOf(highlight("owner", "Owning highlight")),
            ),
        )

        withWebViewFixture(html) { webView ->
            webView.evalSync(
                HighlightsDomPatch.SetNote(
                    annotationId = "owner",
                    accentCssRgba = "rgba(255,193,0,1)",
                    noteText = "Added live",
                ).applyJs(),
            )
            assertEquals(
                "Added live",
                webView.evalSync("document.querySelector('.riffle-note').textContent").trim('"'),
            )
            assertEquals(
                ELIDED_NOTE_LABEL,
                webView.evalSync(
                    "getComputedStyle(document.querySelector('.riffle-note'),'::before')" +
                        ".content.replace(/[\\\"']/g,'')",
                ).trim('"'),
            )
            assertEquals(
                "note",
                webView.evalSync("document.querySelector('.riffle-note').getAttribute('role')").trim('"'),
            )
            assertEquals(
                ELIDED_NOTE_ARIA_LABEL,
                webView.evalSync(
                    "document.querySelector('.riffle-note').getAttribute('aria-label')",
                ).trim('"'),
            )
        }
    }

    private fun highlight(id: String, text: String, note: String? = null) =
        AnnotationEntity(
            id = id,
            sourceId = "source",
            itemId = "item",
            type = AnnotationEntity.TYPE_HIGHLIGHT,
            cfi = "epubcfi(/6/2!/4/2/1:0)",
            color = AnnotationEntity.COLOR_YELLOW,
            note = note,
            textSnippet = text,
            chapterHref = "chapter.xhtml",
            createdAt = 0L,
            updatedAt = 0L,
            originDeviceId = "test",
            lastModifiedByDeviceId = "test",
        )
}
