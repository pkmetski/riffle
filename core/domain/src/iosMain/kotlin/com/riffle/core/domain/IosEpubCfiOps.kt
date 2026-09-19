package com.riffle.core.domain

/** [EpubCfiOps] backed by the ksoup CFI translator (IosEpubCfiTranslator.kt). */
object IosEpubCfiOps : EpubCfiOps {
    override fun extractDocPath(cfi: String): String? = iosExtractCfiDocPath(cfi)

    override fun docPathToProgression(docPath: String, html: String): Double? =
        iosCfiDocPathToProgression(docPath, html)

    override fun progressionToDocPath(progression: Double, html: String): String? =
        iosProgressionToCfiDocPath(progression, html)
}
