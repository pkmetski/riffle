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
            // Migration 9→10 and pre-extension W3C imports defaulted an unknown progression to
            // 0.0. Keep the CFI-derived fallback for those legacy rows when the CFI clearly points
            // later in the resource; a real first-page bookmark resolves to 0.0 as well.
            val persistedProgression = annotation.progression.takeIf {
                it > 0.0 || locations.progression == null
            }
            copy(locations = locations.copy(progression = persistedProgression ?: locations.progression))
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
