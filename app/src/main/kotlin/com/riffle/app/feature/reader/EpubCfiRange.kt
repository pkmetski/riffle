package com.riffle.app.feature.reader

import com.riffle.core.domain.cfiDocPathToProgression
import com.riffle.feature.reader.rangeStartDocPath

// The range-CFI machinery itself now lives in `feature:reader`'s commonMain (issue #1066) so the
// same implementation — and the same tests — run on Android and iOS. What stays here are the
// overloads typed on a jsoup `Document`: `EpubReaderViewModel` caches one parsed document per
// chapter and re-uses it across every annotation in that chapter, which is a JVM-only
// optimisation the shared API deliberately doesn't expose.

/** Pre-parsed-document overload — avoids re-parsing [doc] when multiple highlights share a chapter. */
internal fun highlightStartProgression(rangeCfi: String, doc: org.jsoup.nodes.Document): Double? {
    val startDocPath = rangeStartDocPath(rangeCfi) ?: return null
    return cfiDocPathToProgression(startDocPath, doc)
}

/** Pre-parsed-document + pre-counted totalChars overload — avoids both re-parsing and re-counting. */
internal fun highlightStartProgression(rangeCfi: String, doc: org.jsoup.nodes.Document, totalChars: Long): Double? {
    val startDocPath = rangeStartDocPath(rangeCfi) ?: return null
    return cfiDocPathToProgression(startDocPath, doc, totalChars)
}
