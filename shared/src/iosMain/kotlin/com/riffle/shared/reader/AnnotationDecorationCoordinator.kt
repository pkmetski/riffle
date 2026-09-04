package com.riffle.shared.reader

import com.riffle.core.domain.AnnotationStore
import com.riffle.core.models.Annotation
import com.riffle.feature.reader.EpubNavigatorInterface
import com.riffle.feature.reader.NavigatorDecoration
import com.riffle.feature.reader.NavigatorPageLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

internal const val DECORATION_GROUP_HIGHLIGHTS = "highlights"
internal const val DECORATION_GROUP_BOOKMARKS = "bookmarks"

/**
 * Observes [AnnotationStore] for the open book and re-applies highlight and bookmark
 * decorations via [EpubNavigatorInterface.applyDecorations] whenever annotations change
 * or a new chapter page loads.
 *
 * Lifecycle: call [start] after the navigator is open; call [stop] when the reader closes.
 */
class AnnotationDecorationCoordinator(
    private val sourceId: String,
    private val itemId: String,
    private val annotationStore: AnnotationStore,
    private val navigator: EpubNavigatorInterface,
) {
    private var scope: CoroutineScope? = null

    fun start() {
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = coordinatorScope

        coordinatorScope.launch {
            combine(
                annotationStore.observeHighlights(sourceId, itemId),
                annotationStore.observeBookmarks(sourceId, itemId),
                // Emit a seed immediately so combine doesn't wait for first real page load.
                navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) },
            ) { highlights, bookmarks, _ -> highlights to bookmarks }
                .collect { (highlights, bookmarks) ->
                    navigator.applyDecorations(DECORATION_GROUP_HIGHLIGHTS, toHighlightDecorations(highlights))
                    navigator.applyDecorations(DECORATION_GROUP_BOOKMARKS, toBookmarkDecorations(bookmarks))
                }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        navigator.applyDecorations(DECORATION_GROUP_HIGHLIGHTS, emptyList())
        navigator.applyDecorations(DECORATION_GROUP_BOOKMARKS, emptyList())
    }

    private fun toHighlightDecorations(annotations: List<Annotation>): List<NavigatorDecoration> =
        annotations.mapNotNull { annotationToHighlightDecoration(it) }

    private fun toBookmarkDecorations(annotations: List<Annotation>): List<NavigatorDecoration> =
        annotations.map { annotationToBookmarkDecoration(it) }
}
