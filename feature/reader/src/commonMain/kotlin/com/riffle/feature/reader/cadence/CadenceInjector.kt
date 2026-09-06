package com.riffle.feature.reader.cadence

import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.sentence.FragmentRef
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Bridges the [CadenceDomScript] JS output — a JSON blob returned from `webView.evaluateJavascript(...)`
 * — into typed maps the reader ViewModel consumes via `onCadenceChapterTokenised`.
 *
 * The blob is a JSON-encoded JSON string (Android's WebView wraps `JSON.stringify` returns in an
 * additional quoted string), so we parse twice: first the outer quoted literal, then the inner
 * object. Malformed / null / errored returns short-circuit to [Result.Unsupported] so the reader
 * can hide the Cadence toggle and never hang waiting for a build.
 */
object CadenceInjector {

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        object Unsupported : Result
        data class Ready(
            val quotes: Map<FragmentRef, SentenceQuote>,
            val chapterHrefs: Map<FragmentRef, String>,
        ) : Result
    }

    /**
     * Parse the raw string returned from `evaluateJavascript` when [CadenceDomScript.tokeniseChapterJs]
     * runs. Never throws — every parse failure yields [Result.Unsupported], matching the "no fallback,
     * no error message" WebView-gate posture from issue #403.
     */
    fun parse(rawWebViewJson: String?): Result {
        if (rawWebViewJson.isNullOrBlank() || rawWebViewJson == "null") return Result.Unsupported
        val trimmed = rawWebViewJson.trim()
        val outer = runCatching {
            // WebView.evaluateJavascript wraps string return values in double-quotes and escapes
            // any inner quotes. Detect that and unwrap; otherwise treat the raw payload as JSON.
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                json.decodeFromString(String.serializer(), trimmed)
            } else {
                trimmed
            }
        }.getOrElse { return Result.Unsupported }
        val obj = runCatching { json.parseToJsonElement(outer).jsonObject }
            .getOrElse { return Result.Unsupported }
        if ((obj["supported"] as? JsonPrimitive)?.booleanOrNull != true) return Result.Unsupported

        val quotesJson = obj["quotes"] as? JsonObject ?: return Result.Unsupported
        val hrefsJson = obj["chapterHrefs"] as? JsonObject ?: return Result.Unsupported

        val quotes = mutableMapOf<FragmentRef, SentenceQuote>()
        for ((ref, element) in quotesJson) {
            val entry = element as? JsonObject ?: continue
            quotes[ref] = SentenceQuote(
                before = (entry["before"] as? JsonPrimitive)?.contentOrNull ?: "",
                highlight = (entry["highlight"] as? JsonPrimitive)?.contentOrNull ?: "",
                after = (entry["after"] as? JsonPrimitive)?.contentOrNull ?: "",
            )
        }

        val hrefs = mutableMapOf<FragmentRef, String>()
        for ((ref, element) in hrefsJson) {
            hrefs[ref] = (element as? JsonPrimitive)?.contentOrNull ?: ""
        }

        return Result.Ready(quotes, hrefs)
    }
}
