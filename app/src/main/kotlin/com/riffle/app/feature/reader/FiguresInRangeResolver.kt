package com.riffle.app.feature.reader

import android.webkit.WebView
import com.riffle.core.domain.EmbeddedFigure
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONObject
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Scans a highlight's CFI range for figures it encloses, so [EpubReaderViewModel.createHighlight]
 * can attach them as [EmbeddedFigure]s on the persisted annotation (Task 7).
 *
 * The production path (`window.riffleFiguresInsideRange`, installed by [FigureTapScript]) is a
 * stub as of Task 4 — it always returns `"[]"` because there is no CFI-string→DOM-node resolver
 * wired into the injected JS yet. That resolver is substantial follow-up work (Readium's CFI
 * helpers aren't uniformly available across paginated/vertical/continuous). This interface is the
 * seam: once a real resolver lands, [createHighlight] starts populating `embeddedFigures` with no
 * further ViewModel change required.
 */
fun interface FiguresInRangeResolver {
    suspend fun resolve(cfiRange: String): List<EmbeddedFigure>
}

/**
 * Always-empty resolver bound in production today (see the class doc on
 * [FiguresInRangeResolver]). Swap for [WebViewFiguresInRangeResolver] once the JS CFI resolver
 * exists — no ViewModel change needed, only the Hilt binding.
 */
class NoopFiguresInRangeResolver @Inject constructor() : FiguresInRangeResolver {
    override suspend fun resolve(cfiRange: String): List<EmbeddedFigure> = emptyList()
}

private val figuresJson = Json { ignoreUnknownKeys = true }
private val figuresSerializer = ListSerializer(EmbeddedFigure.serializer())

/**
 * Calls `window.riffleFiguresInsideRange('<cfiRange>')` on [webView] and parses the JSON array
 * result into [EmbeddedFigure]s. Not wired into production DI yet (no seam currently threads the
 * active WebView into the ViewModel); kept here so the class exists once that wiring lands and the
 * JS-side resolver (follow-up work) is implemented.
 */
class WebViewFiguresInRangeResolver(private val webView: WebView) : FiguresInRangeResolver {
    override suspend fun resolve(cfiRange: String): List<EmbeddedFigure> {
        val escaped = JSONObject.quote(cfiRange)
        val raw = suspendCancellableCoroutine<String?> { cont ->
            webView.evaluateJavascript("window.riffleFiguresInsideRange($escaped)") { result ->
                cont.resume(result)
            }
        } ?: return emptyList()
        // `window.riffleFiguresInsideRange` returns a JSON-encoded string (per FigureCaptionWalker's
        // FIGURES_IN_RANGE_JS contract); evaluateJavascript's callback wraps *that* string in an
        // outer layer of JSON encoding, so unwrap once via JSONTokener before parsing the figures.
        val jsonString = (org.json.JSONTokener(raw).nextValue() as? String) ?: raw
        return try {
            figuresJson.decodeFromString(figuresSerializer, jsonString)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
