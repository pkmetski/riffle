package com.riffle.feature.reader.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shared annotations panel. Renders annotation rows in a ModalBottomSheet; shows an empty-state
 * message when the list is empty. iOS had no annotation panel before the shared module.
 */
class AnnotationsPanelTest {

    private fun highlight(id: String, text: String) = Annotation(
        id = id,
        sourceId = "src-1",
        itemId = "item-1",
        type = AnnotationEntity.TYPE_HIGHLIGHT,
        cfi = "epubcfi(/6/4!/4/2/1:0)",
        color = "yellow",
        note = null,
        textSnippet = text,
        textBefore = "",
        textAfter = "",
        chapterHref = "chapter1.xhtml",
        spineIndex = 0,
        progression = 0.0,
        bookmarkTitle = "",
        createdAt = 0L,
        updatedAt = 0L,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun emptyStateLabelShownWhenNoAnnotations() = runComposeUiTest {
        setContent {
            SharedAnnotationsPanel(
                annotations = emptyList(),
                onNavigate = {},
                onDelete = {},
                onRename = { _, _ -> },
                onDismiss = {},
            )
        }
        onNodeWithText("No annotations yet.").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun annotationTextSnippetIsRendered() = runComposeUiTest {
        setContent {
            SharedAnnotationsPanel(
                annotations = listOf(highlight("a1", "The quick brown fox")),
                onNavigate = {},
                onDelete = {},
                onRename = { _, _ -> },
                onDismiss = {},
            )
        }
        onNodeWithText("The quick brown fox").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun clickingRowFiresOnNavigate() = runComposeUiTest {
        val navigated = mutableListOf<String>()
        setContent {
            SharedAnnotationsPanel(
                annotations = listOf(highlight("ann-42", "A highlighted sentence")),
                onNavigate = { navigated += it },
                onDelete = {},
                onRename = { _, _ -> },
                onDismiss = {},
            )
        }
        onNodeWithText("A highlighted sentence").performClick()
        assertEquals(listOf("ann-42"), navigated)
    }
}
