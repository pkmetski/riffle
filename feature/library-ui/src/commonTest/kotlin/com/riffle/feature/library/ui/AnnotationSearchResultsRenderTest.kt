package com.riffle.feature.library.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import com.riffle.core.models.AudiobookBookmark
import com.riffle.feature.library.AnnotationSearchResult
import com.riffle.feature.library.AudiobookBookmarkSearchResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the real annotation-search rows. Excluded from the JVM host-test task (Compose's UI
 * harness needs an Android runtime) and run for real on
 * `:feature:library-ui:iosSimulatorArm64Test` — so these ARE the iOS-path assertions for a
 * screen that did not exist on iOS at all (#1072 §1).
 */
@OptIn(ExperimentalTestApi::class)
class AnnotationSearchResultsRenderTest {

    private val labels = AnnotationSearchLabels.English

    private fun annotation(
        id: String = "a1",
        type: String = AnnotationEntity.TYPE_HIGHLIGHT,
        textSnippet: String = "the highlighted words",
        note: String? = null,
        bookmarkTitle: String = "",
    ) = Annotation(
        id = id,
        sourceId = "src-1",
        itemId = "item-1",
        type = type,
        cfi = "epubcfi(/6/4!/4/2)",
        color = "yellow",
        note = note,
        textSnippet = textSnippet,
        textBefore = "",
        textAfter = "",
        chapterHref = "chapter1.xhtml",
        spineIndex = 0,
        progression = 0.1,
        bookmarkTitle = bookmarkTitle,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun result(a: Annotation) =
        AnnotationSearchResult(annotation = a, bookTitle = "The Book", bookCoverUrl = null)

    private fun bookmarkResult(title: String) = AudiobookBookmarkSearchResult(
        bookmark = AudiobookBookmark(
            id = "bm1",
            sourceId = "src-1",
            itemId = "item-2",
            positionSec = 120.0,
            title = title,
            createdAt = 0L,
        ),
        bookTitle = "The Audiobook",
        bookCoverUrl = null,
    )

    @Test
    fun aHighlightRowShowsItsSnippetNoteAndBook() = runComposeUiTest {
        setContent {
            AnnotationResultRow(
                result = result(annotation(note = "my note")),
                token = "",
                labels = labels,
                onClick = {},
            )
        }

        onNodeWithText("the highlighted words").assertIsDisplayed()
        onNodeWithText("my note").assertIsDisplayed()
        onNodeWithText("The Book").assertIsDisplayed()
    }

    /**
     * The row branches on the stored type token. `annotation.type == "bookmark"` against the
     * database's `"BOOKMARK"` is the exact bug AGENTS.md's constants rule exists for, and it
     * would surface here as a bookmark rendering as an empty highlight.
     */
    @Test
    fun anUntitledBookmarkFallsBackToTheBookmarkLabel() = runComposeUiTest {
        setContent {
            AnnotationResultRow(
                result = result(annotation(type = AnnotationEntity.TYPE_BOOKMARK, bookmarkTitle = "")),
                token = "",
                labels = labels,
                onClick = {},
            )
        }

        onNodeWithText(labels.bookmarkFallbackTitle).assertIsDisplayed()
    }

    /** A bookmark has no note line even when the row is a bookmark carrying one. */
    @Test
    fun aBookmarkRowDoesNotRenderANote() = runComposeUiTest {
        setContent {
            AnnotationResultRow(
                result = result(
                    annotation(
                        type = AnnotationEntity.TYPE_BOOKMARK,
                        bookmarkTitle = "Chapter start",
                        note = "should not show",
                    ),
                ),
                token = "",
                labels = labels,
                onClick = {},
            )
        }

        onNodeWithText("Chapter start").assertIsDisplayed()
        assertTrue(
            runCatching { onNodeWithText("should not show").assertIsDisplayed() }.isFailure,
            "a bookmark row must not render the note line",
        )
    }

    @Test
    fun tappingAnAnnotationRowReportsIt() = runComposeUiTest {
        var clicked: AnnotationSearchResult? = null
        val row = result(annotation())
        setContent {
            AnnotationResultRow(result = row, token = "", labels = labels, onClick = { clicked = row })
        }

        onNodeWithTag("annotation-result-a1").performClick()

        assertEquals(row, clicked)
    }

    @Test
    fun anUntitledAudiobookBookmarkFallsBackToItsOwnLabel() = runComposeUiTest {
        setContent {
            AudiobookBookmarkResultRow(
                result = bookmarkResult(""),
                token = "",
                labels = labels,
                onClick = {},
            )
        }

        onNodeWithText(labels.audiobookBookmarkFallbackTitle).assertIsDisplayed()
        onNodeWithText("The Audiobook").assertIsDisplayed()
    }

    @Test
    fun tappingAnAudiobookBookmarkRowReportsIt() = runComposeUiTest {
        var clicked: AudiobookBookmarkSearchResult? = null
        val row = bookmarkResult("Good bit")
        setContent {
            AudiobookBookmarkResultRow(result = row, token = "", labels = labels, onClick = { clicked = row })
        }

        onNodeWithTag("audiobook-bookmark-result-bm1").performClick()

        assertEquals(row, clicked)
    }
}
