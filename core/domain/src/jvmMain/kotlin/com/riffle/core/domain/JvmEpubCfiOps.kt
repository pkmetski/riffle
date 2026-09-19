package com.riffle.core.domain

/** [EpubCfiOps] backed by the jsoup CFI translator (EpubCfiTranslator.kt). */
object JvmEpubCfiOps : EpubCfiOps {
    override fun extractDocPath(cfi: String): String? = extractCfiDocPath(cfi)

    override fun docPathToProgression(docPath: String, html: String): Double? =
        cfiDocPathToProgression(docPath, html)

    override fun progressionToDocPath(progression: Double, html: String): String? =
        progressionToCfiDocPath(progression, html)
}
