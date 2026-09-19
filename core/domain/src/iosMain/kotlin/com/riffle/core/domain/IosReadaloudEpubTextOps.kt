package com.riffle.core.domain

/** ksoup-backed [ReadaloudEpubTextOps] for iOS. */
object IosReadaloudEpubTextOps : ReadaloudEpubTextOps {
    override val cfiOps: EpubCfiOps = IosEpubCfiOps

    override fun progressionsOfElementIds(html: String, elementIds: Set<String>): Map<String, Double> =
        IosEpubTextChars.progressionsOfElementIds(html, elementIds)

    override val sentenceSpans: SentenceSpanReader = IosSentenceSpanReader
}
