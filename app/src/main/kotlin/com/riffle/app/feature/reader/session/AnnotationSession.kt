package com.riffle.app.feature.reader.session

/**
 * Backwards-compatibility re-export. [AnnotationSession] was lifted to `feature:reader`
 * commonMain (issue #1241) so iOS can share the same session. All Android call sites use this
 * alias unchanged.
 */
typealias AnnotationSession = com.riffle.feature.reader.AnnotationSession
