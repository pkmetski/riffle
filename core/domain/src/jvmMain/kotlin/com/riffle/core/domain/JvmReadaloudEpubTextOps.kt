package com.riffle.core.domain

/** JVM [ReadaloudEpubTextOps]. Uses the shared Ksoup CFI ops. */
object JvmReadaloudEpubTextOps : ReadaloudEpubTextOps {
    override val cfiOps: EpubCfiOps = KsoupEpubCfiOps

    override fun progressionsOfElementIds(html: String, elementIds: Set<String>): Map<String, Double> =
        EpubTextChars.progressionsOfElementIds(html, elementIds)

    override val sentenceSpans: SentenceSpanReader = JvmSentenceSpanReader
}
