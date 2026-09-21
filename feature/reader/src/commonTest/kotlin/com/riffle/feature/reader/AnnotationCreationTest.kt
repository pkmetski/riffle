package com.riffle.feature.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmbeddedFigure
import com.riffle.core.models.EmphasisStyle
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two decisions that turn a selection into a stored highlight, now that both platforms make
 * them through the same functions.
 *
 * These ran only as Android ViewModel behaviour before, which is why iOS could not create an
 * annotation without re-deriving the whole policy. In `commonTest`, so the same assertions
 * execute on `iosSimulatorArm64` — the platform whose ksoup DOM ops have to agree with jsoup's
 * character counting for any of it to hold.
 */
class AnnotationCreationTest {

    // Two paragraphs, ASCII, no figures. The readable-character stream skips the blank text
    // nodes between block elements, which is the whole reason the anchor cannot be derived from
    // `snippet.length`.
    private val html = """
        <html><body>
        <p>There was a man and he had eight sons.</p>
        <p>Apart from that, he was nothing more than a comma on the page of History.</p>
        </body></html>
    """.trimIndent()

    private fun annotation(
        id: String,
        snippet: String,
        before: String,
        color: String = "yellow",
        type: String = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi: String = "epubcfi(/6/2!/4/2,/1:0,/1:5)",
        imageHref: String? = null,
        note: String? = null,
    ) = Annotation(
        id = id,
        sourceId = "src",
        itemId = "item",
        type = type,
        cfi = cfi,
        color = color,
        note = note,
        textSnippet = snippet,
        textBefore = before,
        textAfter = "",
        chapterHref = "ch1.xhtml",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
        imageHref = imageHref,
    )

    // ── buildHighlightAnchor ────────────────────────────────────────────────────────────────

    @Test
    fun anchorUsesTheSelectionsOwnPositionNotThePagesProgression() {
        // Readium reports the PAGE's progression in paginated mode, so every highlight made on
        // one page would share it and the annotations panel — which sorts by progression —
        // would list them in creation order instead of reading order.
        val first = buildHighlightAnchor(html, spineIndex = 0, snippet = "There was a man", textBefore = "", pageProgression = 0.9)
        val second = buildHighlightAnchor(html, spineIndex = 0, snippet = "page of History", textBefore = "", pageProgression = 0.9)
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first.progression < second.progression, "reading order must survive: $first / $second")
        assertFalse(first.progression == 0.9, "the page progression must not be stored verbatim")
    }

    @Test
    fun anchorFallsBackToThePageProgressionWhenTheSnippetIsNotInTheChapter() {
        // A revised resource, or a selection that crossed markup the readable-text walk skips.
        // The CFI then comes from the progression path rather than the located-char path.
        val anchor = buildHighlightAnchor(
            html,
            spineIndex = 0,
            snippet = "text that is not in this chapter at all",
            textBefore = "",
            pageProgression = 0.25,
        )
        assertNotNull(anchor)
        assertEquals(0.25, anchor.progression)
    }

    @Test
    fun theCfiRangeIsBuiltFromTheSpineStepOfTheChapter() {
        // spineStep = (spineIndex + 1) * 2 — a CFI built off the wrong step resolves in the
        // wrong chapter on the other device.
        val first = buildHighlightAnchor(html, 0, "There was a man", "", 0.0)
        val third = buildHighlightAnchor(html, 2, "There was a man", "", 0.0)
        assertNotNull(first)
        assertNotNull(third)
        assertTrue(first.cfiRange.startsWith("epubcfi(/6/2"), first.cfiRange)
        assertTrue(third.cfiRange.startsWith("epubcfi(/6/6"), third.cfiRange)
    }

    @Test
    fun aSelectionSpanningAParagraphBreakIsStillLocated() {
        // Readium hands back "…sons.\nApart…", but the readable-character stream has no blank
        // text node between the two <p>s — it reads "…sons.Apart…". A locator that compared the
        // two verbatim would fail to find the snippet and silently fall back to the page
        // progression, which is how a cross-paragraph highlight ends up anchored to the top of
        // the page instead of to its own text.
        val anchor = buildHighlightAnchor(html, 0, "eight sons.\nApart from that", "", pageProgression = 0.9)
        assertNotNull(anchor)
        assertFalse(anchor.progression == 0.9, "fell back to the page progression: $anchor")
        assertTrue(anchor.figureRangeEndChar > anchor.figureRangeStartChar, "$anchor")
    }

    // ── planHighlightCommit ─────────────────────────────────────────────────────────────────

    private fun draft(snippet: String, before: String, figures: List<EmbeddedFigure>? = null) = draftFieldsOf(
        cfiRange = "epubcfi(/6/2!/4/2,/1:0,/1:5)",
        textSnippet = snippet,
        textBefore = before,
        textAfter = "",
        progression = 0.1,
        embeddedFigures = figures,
    )

    @Test
    fun aDraftThatOverlapsAnExistingHighlightAbsorbsIt() {
        val existing = annotation("old", snippet = "was a man", before = "There ")
        val plan = planHighlightCommit(
            html = html,
            draftFields = draft("There was a man and he had", ""),
            draftSpineIndex = 0,
            draftChapterHref = "ch1.xhtml",
            draftColor = "yellow",
            draftEmphasisStyles = emptySet(),
            candidates = listOf(existing),
            emphasisPool = emptyList(),
            imageAnnotations = emptyList(),
        )
        assertContentEquals(listOf("old"), plan.deleteHighlightIds)
        assertFalse(plan.carrySnippetHtml, "a merged row's snippet is wider than the draft's HTML")
    }

    @Test
    fun anOverlapMergeCascadesTheVictimsEmphasisRows() {
        // ADR 0056 §4: an emphasis row left behind has no live anchor — the panel filters it
        // out and the DOM injector does not, so the text stays formatted forever.
        val victimCfi = "epubcfi(/6/2!/4/2,/1:6,/1:15)"
        val existing = annotation("old", snippet = "was a man", before = "There ", cfi = victimCfi)
        val emphasis = annotation(
            "em",
            snippet = "was a man",
            before = "There ",
            type = AnnotationEntity.TYPE_EMPHASIS,
            cfi = victimCfi,
        ).copy(emphasisStyles = setOf(EmphasisStyle.BOLD))
        val plan = planHighlightCommit(
            html = html,
            draftFields = draft("There was a man and he had", ""),
            draftSpineIndex = 0,
            draftChapterHref = "ch1.xhtml",
            draftColor = "yellow",
            // The draft must carry the same styles for the victim to be merge-eligible at all —
            // absorbing a bold range into a plain one would silently drop the formatting.
            draftEmphasisStyles = setOf(EmphasisStyle.BOLD),
            candidates = listOf(existing),
            emphasisPool = listOf(emphasis),
            imageAnnotations = emptyList(),
        )
        assertContentEquals(listOf("old"), plan.deleteHighlightIds)
        assertContentEquals(listOf("em"), plan.deleteEmphasisIds)
    }

    @Test
    fun aDraftWithNoNeighboursKeepsItsOwnFieldsAndItsFormattedHtml() {
        val plan = planHighlightCommit(
            html = html,
            draftFields = draft("comma on the page", "than a "),
            draftSpineIndex = 0,
            draftChapterHref = "ch1.xhtml",
            draftColor = "yellow",
            draftEmphasisStyles = emptySet(),
            candidates = emptyList(),
            emphasisPool = emptyList(),
            imageAnnotations = emptyList(),
        )
        assertEquals("comma on the page", plan.fields.textSnippet)
        assertTrue(plan.deleteHighlightIds.isEmpty())
        assertTrue(plan.carrySnippetHtml, "an unmerged create may keep its inline-formatted HTML")
    }

    @Test
    fun aStandaloneImageAnnotationIsAbsorbedWhenTheHighlightNowEnclosesItsFigure() {
        val figure = EmbeddedFigure(href = "OEBPS/images/plot.png", svg = null, caption = "", order = 0)
        val standalone = annotation(
            "img",
            snippet = "",
            before = "",
            type = AnnotationEntity.TYPE_IMAGE,
            imageHref = "images/plot.png",
        )
        val plan = planHighlightCommit(
            html = html,
            draftFields = draft("comma on the page", "than a ", figures = listOf(figure)),
            draftSpineIndex = 0,
            draftChapterHref = "ch1.xhtml",
            draftColor = "yellow",
            draftEmphasisStyles = emptySet(),
            candidates = emptyList(),
            emphasisPool = emptyList(),
            imageAnnotations = listOf(standalone),
        )
        assertContentEquals(
            listOf("img"),
            plan.deleteImageIds,
            "matched by filename, the way FigureBorderDecoration matches",
        )
    }

    @Test
    fun anUnreadableChapterStillProducesAUsablePlan() {
        // The reader must not refuse to create a highlight because the resource could not be
        // read; it just cannot merge.
        val plan = planHighlightCommit(
            html = null,
            draftFields = draft("There was a man", ""),
            draftSpineIndex = 0,
            draftChapterHref = "ch1.xhtml",
            draftColor = "yellow",
            draftEmphasisStyles = emptySet(),
            candidates = listOf(annotation("old", "was a man", "There ")),
            emphasisPool = emptyList(),
            imageAnnotations = emptyList(),
        )
        assertTrue(plan.deleteHighlightIds.isEmpty())
        assertEquals("There was a man", plan.fields.textSnippet)
        assertTrue(plan.carrySnippetHtml)
    }

    // ── The origin-font sentinel ────────────────────────────────────────────────────────────

    @Test
    fun theOriginFontSentinelIsTheValueTheHealQueryMatchesOn() {
        // `AnnotationStore.healSentinelOriginFontFamily` matches this exact string. A different
        // marker on iOS would leave every annotation made on an iPhone permanently unhealed.
        assertEquals("serif", FALLBACK_ORIGIN_FONT_FAMILY)
    }

    // ── annotationListLabel ─────────────────────────────────────────────────────────────────

    @Test
    fun theListLabelMarksANoteSoAHighlightWithOneIsDistinguishable() {
        val plain = annotationListLabel(annotation("a", "some text", ""))
        val noted = annotationListLabel(annotation("b", "some text", "", note = "why this matters"))
        assertFalse(plain.endsWith("📝"))
        assertTrue(noted.endsWith("📝"), noted)
    }

    @Test
    fun theListLabelUsesABookmarksTitleAndCollapsesWhitespace() {
        val bookmark = annotation("bm", snippet = "", before = "", type = AnnotationEntity.TYPE_BOOKMARK)
            .copy(bookmarkTitle = "Chapter  2 ·\n40%")
        val label = annotationListLabel(bookmark)
        assertTrue(label.contains("Chapter 2 · 40%"), label)
        assertFalse(label.contains('\n'), label)
    }

    @Test
    fun theListLabelClipsALongSnippet() {
        val long = annotationListLabel(annotation("c", "x".repeat(200), ""), maxSnippet = 20)
        assertTrue(long.endsWith("…"), long)
        assertTrue(long.length <= 21, "got ${long.length}: $long")
    }

    @Test
    fun anImageAnnotationWithNoSnippetStillHasALabel() {
        val label = annotationListLabel(
            annotation("img", snippet = "", before = "", type = AnnotationEntity.TYPE_IMAGE, imageHref = "a.png"),
        )
        assertFalse(label.isBlank())
        assertNull(label.firstOrNull { it == '\n' })
    }
}
