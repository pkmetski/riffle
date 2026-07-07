package com.riffle.app.feature.reader.cadence

import com.riffle.core.domain.SentenceQuote
import com.riffle.core.domain.sentence.FragmentRef
import org.json.JSONObject

/**
 * Bridges the [CadenceDomScript] JS output — a JSON blob returned from `webView.evaluateJavascript(...)`
 * — into typed maps the reader ViewModel consumes via `onCadenceChapterTokenised`.
 *
 * The blob is a JSON-encoded JSON string (Android's WebView wraps `JSON.stringify` returns in an
 * additional quoted string), so we parse twice: first the outer quoted literal, then the inner
 * object. Malformed / null / errored returns short-circuit to [Result.Unsupported] so the reader
 * can hide the Cadence toggle and never hang waiting for a build.
 */
internal object CadenceInjector {

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
        val outer = runCatching {
            // WebView.evaluateJavascript wraps string return values in double-quotes and escapes
            // any inner quotes. Detect that and unwrap; otherwise treat the raw payload as JSON.
            val trimmed = rawWebViewJson.trim()
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                unescapeJsonStringLiteral(trimmed.substring(1, trimmed.length - 1))
            } else {
                trimmed
            }
        }.getOrElse { return Result.Unsupported }
        val obj = runCatching { JSONObject(outer) }.getOrElse { return Result.Unsupported }
        if (!obj.optBoolean("supported", false)) return Result.Unsupported

        val quotesJson = obj.optJSONObject("quotes") ?: return Result.Unsupported
        val hrefsJson = obj.optJSONObject("chapterHrefs") ?: return Result.Unsupported

        val quotes = mutableMapOf<FragmentRef, SentenceQuote>()
        val keys = quotesJson.keys()
        while (keys.hasNext()) {
            val ref = keys.next()
            val entry = quotesJson.optJSONObject(ref) ?: continue
            quotes[ref] = SentenceQuote(
                before = entry.optString("before", ""),
                highlight = entry.optString("highlight", ""),
                after = entry.optString("after", ""),
            )
        }

        val hrefs = mutableMapOf<FragmentRef, String>()
        val hrefKeys = hrefsJson.keys()
        while (hrefKeys.hasNext()) {
            val ref = hrefKeys.next()
            hrefs[ref] = hrefsJson.optString(ref, "")
        }

        return Result.Ready(quotes, hrefs)
    }

    /** Minimal JSON string-literal unescape covering the sequences [CadenceDomScript] can emit. */
    private fun unescapeJsonStringLiteral(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '\\' -> out.append('\\')
                    '"' -> out.append('"')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    '/' -> out.append('/')
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            val code = runCatching { Integer.parseInt(hex, 16) }.getOrNull()
                            if (code != null) {
                                out.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        out.append(n)
                    }
                    else -> out.append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
