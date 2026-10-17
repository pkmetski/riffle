package com.riffle.core.domain

/** [EpubCfiOps] backed by the ksoup CFI translator. */
object KsoupEpubCfiOps : EpubCfiOps {
    override fun extractDocPath(cfi: String): String? = ksoupExtractCfiDocPath(cfi)

    override fun docPathToProgression(docPath: String, html: String): Double? =
        ksoupCfiDocPathToProgression(docPath, html)

    override fun progressionToDocPath(progression: Double, html: String): String? =
        ksoupProgressionToCfiDocPath(progression, html)
}
