package com.riffle.core.domain

/** jsoup-backed [ReadaloudEpubTextOps] for Android/JVM. */
object JvmReadaloudEpubTextOps : ReadaloudEpubTextOps {
    override val cfiOps: EpubCfiOps = JvmEpubCfiOps

    override fun progressionsOfElementIds(html: String, elementIds: Set<String>): Map<String, Double> =
        EpubTextChars.progressionsOfElementIds(html, elementIds)

    override val sentenceSpans: SentenceSpanReader = JvmSentenceSpanReader
}
