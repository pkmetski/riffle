package com.riffle.app.feature.reader.cadence

import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.sentence.FragmentRef
import com.riffle.core.domain.sentence.SentenceSource
import kotlinx.coroutines.CompletableDeferred

/**
 * Cadence's [SentenceSource]: tokenises the live chapter DOM with the WebView's
 * `Intl.Segmenter` (locale from EPUB `xml:lang`), wraps every sentence in a `<span id="cd-N">`,
 * and returns the resulting `FragmentRef → SentenceQuote` + `FragmentRef → chapterHref` maps.
 *
 * The actual DOM tokenisation runs inside the reader WebView — this class is the Kotlin-side
 * handle. The injection layer (`CadenceDomScript` + the reader's script injector) calls
 * [supplyResult] once JS has finished; concurrent callers of [loadAll] / [chapterHrefs] all await
 * that same completion via [CompletableDeferred].
 *
 * See [ADR 0039](../../../../../../../../docs/adr/0039-sentence-playback-pipeline-shared-with-cadence.md)
 * for the shared [SentenceSource] contract between Readaloud (SMIL) and Cadence (DOM).
 */
class DomSentenceSource : SentenceSource {

    private val result = CompletableDeferred<Result>()

    /**
     * Called by the injection layer once JS has finished span-wrapping the DOM and produced the
     * fragment→quote map. Idempotent — a second call is silently ignored (source is per-book, and
     * per-book means one build).
     */
    fun supplyResult(quotes: Map<FragmentRef, SentenceQuote>, hrefs: Map<FragmentRef, String>) {
        result.complete(Result(quotes, hrefs))
    }

    /**
     * Called when JS reports the platform is not supported (missing `Intl.Segmenter`) or when the
     * publication has no readable chapters. Completes both maps as empty — downstream Cadence
     * treats an empty fragment ordering as "nothing to tick" and the top-bar toggle hides in
     * that case (see `showCadence` prefs + the WebView-gate flag).
     */
    fun supplyEmpty() {
        result.complete(Result(emptyMap(), emptyMap()))
    }

    override suspend fun loadAll(): Map<FragmentRef, SentenceQuote> = result.await().quotes
    override suspend fun chapterHrefs(): Map<FragmentRef, String> = result.await().hrefs

    private data class Result(
        val quotes: Map<FragmentRef, SentenceQuote>,
        val hrefs: Map<FragmentRef, String>,
    )
}
