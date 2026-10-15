package com.riffle.feature.reader

import com.riffle.core.domain.DispatcherProvider
import com.riffle.core.domain.ExtractedEpub
import com.riffle.core.domain.ReadaloudTextQuotes
import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.SentenceSpanReader
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the sentence-quote build pipeline and its two derived StateFlows.
 *
 * Platform-specific EPUB extraction is injected via [extractEpub] so this class can live in
 * commonMain. Android passes `EpubContentExtractor.extract(File(path))`; iOS passes
 * `IosEpubContentExtractor.extract(path)`.
 */
internal class ReadaloudQuoteBuilder(
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
    private val extractEpub: suspend (bundlePath: String) -> ExtractedEpub?,
    private val spanReader: SentenceSpanReader,
) {

    private val _sentenceQuotes = MutableStateFlow<Map<String, SentenceQuote>>(emptyMap())
    val sentenceQuotes: StateFlow<Map<String, SentenceQuote>> = _sentenceQuotes

    private val _sentenceChapters = MutableStateFlow<Map<String, String>>(emptyMap())
    val sentenceChapters: StateFlow<Map<String, String>> = _sentenceChapters

    /** The bundle path (or streaming sidecar path) that will provide the source SMIL/text. */
    @kotlin.concurrent.Volatile var quoteBundle: String? = null

    @kotlin.concurrent.Volatile
    internal var started = false
        private set

    private var buildJob: Job? = null

    fun build(bundlePath: String) {
        if (started) return
        started = true
        buildJob = scope.launch(dispatchers.io) {
            try {
                val chapters = extractEpub(bundlePath)?.chapters ?: return@launch
                _sentenceQuotes.value = ReadaloudTextQuotes.build(chapters, spanReader)
                _sentenceChapters.value = ReadaloudTextQuotes.sentenceChapterHrefs(chapters, spanReader)
            } catch (e: Throwable) {
                logger.e(LogChannel.Readaloud, e) { "buildSentenceQuotes failed" }
            }
        }
    }

    suspend fun ensureBuilt() {
        val bundle = quoteBundle ?: return
        if (sentenceQuotes.value.isNotEmpty()) return
        build(bundle)
        buildJob?.join()
    }

    fun reset() {
        quoteBundle = null
        started = false
    }
}
