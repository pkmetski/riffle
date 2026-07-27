package com.riffle.app.feature.reader.session

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.models.Annotation
import org.readium.r2.shared.publication.Locator

/**
 * Adds the persisted TextQuote to a resolved highlight locator.
 *
 * CFI resolution reconstructs chapter/progression/fragment without access to the annotation row.
 * Readium needs highlight/before/after to resolve the exact DOM Range, so every annotation entry
 * path enriches its locator once the matching row is available. Progression stays intact as the
 * fallback when a legacy or stale quote no longer matches the publication.
 */
internal fun Locator.withAnnotationTextQuote(annotation: Annotation?): Locator {
    if (
        annotation?.type != AnnotationEntity.TYPE_HIGHLIGHT ||
        annotation.textSnippet.isBlank()
    ) {
        return this
    }
    return copy(
        text = Locator.Text(
            before = annotation.textBefore,
            highlight = annotation.textSnippet,
            after = annotation.textAfter,
        ),
    )
}
