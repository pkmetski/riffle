package com.riffle.app.feature.reader.readaloud

import com.riffle.app.feature.reader.session.ReadaloudQuoteBuilder
import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.sentence.FragmentRef
import com.riffle.core.domain.sentence.SentenceSource

/**
 * Readaloud's [SentenceSource]: wraps the existing [ReadaloudQuoteBuilder] pipeline (Storyteller
 * SMIL/text extraction) behind the shared interface.
 *
 * This is a pure wrap — no logic moves here. [ReadaloudSession][com.riffle.app.feature.reader.session.ReadaloudSession]
 * still consumes [ReadaloudQuoteBuilder.sentenceQuotes]/[ReadaloudQuoteBuilder.sentenceChapters]
 * directly as it does today; this class is present but unused until the swap (ADR 0039).
 */
internal class SidecarSentenceSource(
    private val builder: ReadaloudQuoteBuilder,
) : SentenceSource {

    override suspend fun loadAll(): Map<FragmentRef, SentenceQuote> {
        builder.ensureBuilt()
        return builder.sentenceQuotes.value
    }

    override suspend fun chapterHrefs(): Map<FragmentRef, String> {
        builder.ensureBuilt()
        return builder.sentenceChapters.value
    }
}
