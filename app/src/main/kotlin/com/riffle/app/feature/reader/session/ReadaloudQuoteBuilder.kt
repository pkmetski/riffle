package com.riffle.app.feature.reader.session

import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.EpubContentExtractor
import com.riffle.core.domain.ReadaloudTextQuotes
import com.riffle.core.domain.SentenceQuote
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the sentence-quote build pipeline and its two derived StateFlows.
 *
 * Extracted from ReadaloudSession as part of #380. The build is one-shot per book (a rapid
 * pause→resume must not re-launch it); [reset] returns the builder to its initial state on
 * book close.
 */
internal class ReadaloudQuoteBuilder(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) {

    private val _sentenceQuotes = MutableStateFlow<Map<String, SentenceQuote>>(emptyMap())
    val sentenceQuotes: StateFlow<Map<String, SentenceQuote>> = _sentenceQuotes

    private val _sentenceChapters = MutableStateFlow<Map<String, String>>(emptyMap())
    val sentenceChapters: StateFlow<Map<String, String>> = _sentenceChapters

    /** The bundle (or streaming sidecar) that will provide the source SMIL/text on the next build. */
    @Volatile var quoteBundle: File? = null

    @Volatile
    internal var started = false
        private set

    /** Extracts sentence quotes from [bundle]. One-shot: safe to call repeatedly. */
    fun build(bundle: File) {
        if (started) return
        started = true
        scope.launch(dispatchers.io) {
            try {
                val chapters = EpubContentExtractor.extract(bundle)?.chapters ?: return@launch
                _sentenceQuotes.value = ReadaloudTextQuotes.build(chapters)
                _sentenceChapters.value = ReadaloudTextQuotes.sentenceChapterHrefs(chapters)
            } catch (e: Throwable) {
                logger.e(LogChannel.Readaloud, e) { "buildSentenceQuotes failed" }
            }
        }
    }

    /** Restore the initial state so the next book open can re-seed the builder. */
    fun reset() {
        quoteBundle = null
        started = false
    }
}
