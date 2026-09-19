package com.riffle.app.feature.reader

import com.riffle.app.feature.reader.session.AnnotationSession
import com.riffle.feature.reader.MergedDraftFields

/**
 * Trivially lift a draft's own fields into [MergedDraftFields] for the no-overlap path.
 *
 * Stays in `app` while [AnnotationSession] does: [MergedDraftFields] itself is shared
 * (`feature:reader` commonMain, issue #1066), but this adapter is the one part of the overlap-merge
 * surface that is typed on the Android-side session.
 */
internal fun AnnotationSession.DraftAnnotation.toDraftFields(): MergedDraftFields =
    MergedDraftFields(
        cfiRange = cfiRange,
        textSnippet = textSnippet,
        textBefore = textBefore,
        textAfter = textAfter,
        progression = progression,
        embeddedFigures = embeddedFigures,
    )
