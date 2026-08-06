package com.riffle.app.feature.reader.session

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import org.readium.r2.shared.publication.Locator

/**
 * Restores the annotation-owned navigation anchor after resolving its translated CFI.
 *
 * The translated CFI's character-count progression is approximate: it does not preserve the
 * exact Readium page boundary captured when a bookmark was created. Bookmarks therefore restore
 * the live-reader progression persisted in their row. Highlights instead attach their persisted
 * TextQuote so Readium can resolve the exact DOM range, retaining CFI progression as a fallback.
 */
internal fun Locator.withAnnotationNavigationAnchor(annotation: Annotation?): Locator =
    when {
        annotation?.type == AnnotationEntity.TYPE_BOOKMARK -> {
            val anchor = annotation.fragmentAnchor
            if (anchor != null) {
                // New-style bookmark: element-anchored. Also restore progression as JS fallback
                // if getElementById misses the element (e.g. element id changed in a revised EPUB).
                val persistedProgression = annotation.progression.takeIf { it > 0.0 }
                copy(locations = locations.copy(
                    fragments = listOf(anchor),
                    progression = persistedProgression ?: locations.progression,
                ))
            } else {
                // Legacy bookmark: no captured element id. Clear any CFI-derived container fragments
                // (section-level, always resolve to column 0) and use stored progression instead.
                // The production cfiLocatorResolver (EpubReaderViewModel.cfiStringToLocator) calls
                // extractAnchorFromCfi and puts the result into locations.fragments — so even a
                // legacy bookmark arrives here with a container-level id like "section#ch01" in
                // fragments. If we don't clear it, snapAfterGoTo sees a non-empty fragments list,
                // takes the element-snap path, and always lands at column 0 (the container's left).
                val persistedProgression = annotation.progression.takeIf {
                    it > 0.0 || locations.progression == null
                }
                copy(locations = locations.copy(
                    fragments = emptyList(),
                    progression = persistedProgression ?: locations.progression,
                ))
            }
        }
        annotation?.type == AnnotationEntity.TYPE_HIGHLIGHT && annotation.textSnippet.isNotBlank() -> copy(
            text = Locator.Text(
                before = annotation.textBefore,
                highlight = annotation.textSnippet,
                after = annotation.textAfter,
            ),
        )
        else -> this
    }
