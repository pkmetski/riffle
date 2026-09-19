package com.riffle.core.domain

/**
 * The three epub.js-CFI ↔ progression primitives [DefaultPositionTranslator] needs (ADR 0013).
 *
 * They are DOM-library-bound — jsoup on JVM, ksoup on Kotlin/Native (org.jsoup has no K/N target)
 * — so they are injected rather than called directly, which is what lets the translator itself be
 * shared. Both implementations must walk identically: the same CFI has to resolve to the same
 * progression on either platform or a synced position lands in the wrong place.
 */
interface EpubCfiOps {
    fun extractDocPath(cfi: String): String?
    fun docPathToProgression(docPath: String, html: String): Double?
    fun progressionToDocPath(progression: Double, html: String): String?
}
