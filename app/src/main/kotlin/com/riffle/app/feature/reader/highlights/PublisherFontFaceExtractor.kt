package com.riffle.app.feature.reader.highlights

import java.util.Base64

/**
 * Extracts `@font-face` rules from a source EPUB's stylesheets and rewrites each `src: url(...)`
 * to an inline `data:` URI so the elided-view synthesised HTML can render the publisher's actual
 * face (elided-view-serif-font-regression follow-up).
 *
 * The synthesised elided document is a Publication distinct from the source book — the source
 * book's Readium asset server does not answer requests routed against the synthesised container,
 * so plain `src: url(font_rsrc2H2.ttf)` in a captured `@font-face` rule can't resolve. Inlining
 * every referenced font as base64 gives the WebView a self-contained document that renders the
 * captured `originFontFamily` value ("Nimbusromno9l" et al) in the correct face when the reader
 * is in Original / Publisher font mode.
 *
 * Size trade-off: font TTFs in a typical EPUB are 15-40 KB each; a book like *A Philosophy of
 * Software Design* embeds ~8 faces (~250 KB total). Inlining them once per synthesised chapter
 * HTML is acceptable — the elided document is small compared to the source book and the WebView
 * caches parsed `@font-face` blocks per document.
 */
internal object PublisherFontFaceExtractor {

    /**
     * Given the raw bytes of every CSS file discovered inside the source EPUB and a resolver
     * from a font-file path (relative to the CSS file's own directory) to the font's bytes,
     * returns a single CSS string containing every `@font-face` rule with its `src` rewritten
     * to `url("data:font/...;base64,...")`.
     *
     * Empty when no `@font-face` was found or every referenced font failed to resolve — the
     * caller then omits the `<style>` block entirely rather than emit a hollow declaration.
     */
    fun extract(
        cssFiles: List<Pair<String, ByteArray>>,
        fontResolver: (path: String) -> ByteArray?,
    ): String {
        val out = StringBuilder()
        for ((cssPath, bytes) in cssFiles) {
            val css = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: continue
            val cssDir = cssPath.substringBeforeLast('/', "")
                .let { if (it.isEmpty()) "" else "$it/" }
            for (rule in fontFaceRules(css)) {
                val rewritten = rewriteRule(rule, cssDir, fontResolver) ?: continue
                out.append(rewritten)
                out.append('\n')
            }
        }
        return out.toString()
    }

    /**
     * Splits [css] into raw `@font-face { ... }` rule bodies (INCLUDING the outer `@font-face`
     * keyword and brace pair). Skips balanced braces inside the rule body so a nested `{}` (very
     * rare in `@font-face` but permitted) doesn't split the rule mid-way. Whitespace/comments
     * outside `@font-face` are ignored.
     */
    private fun fontFaceRules(css: String): List<String> {
        val stripped = stripComments(css)
        val results = mutableListOf<String>()
        var i = 0
        val marker = "@font-face"
        while (true) {
            val start = stripped.indexOf(marker, i, ignoreCase = true)
            if (start < 0) break
            val openBrace = stripped.indexOf('{', start + marker.length)
            if (openBrace < 0) break
            // Skip past whitespace between marker and brace; anything else means a false match.
            val between = stripped.substring(start + marker.length, openBrace)
            if (between.any { !it.isWhitespace() }) { i = start + marker.length; continue }
            var depth = 1
            var j = openBrace + 1
            while (j < stripped.length && depth > 0) {
                when (stripped[j]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                j++
            }
            if (depth != 0) break
            results += stripped.substring(start, j)
            i = j
        }
        return results
    }

    /** Removes `/* … */` block comments so `@font-face` inside a comment isn't matched. */
    private fun stripComments(css: String): String {
        val sb = StringBuilder(css.length)
        var i = 0
        while (i < css.length) {
            if (i + 1 < css.length && css[i] == '/' && css[i + 1] == '*') {
                val end = css.indexOf("*/", i + 2)
                i = if (end < 0) css.length else end + 2
            } else {
                sb.append(css[i])
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Rewrites every `src: url(<path>)` inside [rule] to `src: url("data:<mime>;base64,<b64>")`
     * by resolving `<path>` (relative to [cssDir]) through [fontResolver]. Preserves the
     * comma-separated `local()`/`format()`/etc entries around each `url()`. Returns null when
     * no `url()` in the rule could be resolved to bytes — the caller then drops the rule
     * entirely (better than emitting a `@font-face` with an unresolvable `src`, which some
     * WebViews treat as "font unusable" and refuse to fall back).
     */
    private fun rewriteRule(
        rule: String,
        cssDir: String,
        fontResolver: (path: String) -> ByteArray?,
    ): String? {
        var anyResolved = false
        val rewritten = URL_REGEX.replace(rule) { match ->
            val rawPath = match.groupValues[1].trim().trim('\'', '"')
            // Already inline — count as resolved so the whole rule survives, but pass the
            // declaration through untouched (the WebView handles the base64 payload directly).
            if (rawPath.startsWith("data:", ignoreCase = true)) {
                anyResolved = true
                return@replace match.value
            }
            // Try the CSS-dir-relative resolution first (the normal EPUB case), then the raw
            // path as given (in case the caller already handed us a package-absolute path).
            // Finally, if the raw path starts with '/', ALSO try common EPUB-root prefixes:
            // most EPUBs stash content under `OEBPS/` (`EPUB/` in newer builds), and a
            // package-absolute `url('/fonts/x.ttf')` means "relative to the EPUB package root"
            // — which is the ZIP's OEBPS directory, not the ZIP root. Without this fallback,
            // the extractor dropped `@font-face` rules on hand-authored EPUBs that use
            // package-absolute URLs and the elided view fell through to browser-default serif
            // (review finding).
            val resolved = fontResolver(resolveAgainst(cssDir, rawPath))
                ?: fontResolver(rawPath)
                ?: if (rawPath.startsWith('/')) {
                    val bare = rawPath.trimStart('/')
                    EPUB_ROOT_PREFIXES.firstNotNullOfOrNull { fontResolver("$it$bare") }
                } else null
                ?: return@replace match.value
            anyResolved = true
            val mime = mimeForFontPath(rawPath)
            val b64 = Base64.getEncoder().encodeToString(resolved)
            "url(\"data:$mime;base64,$b64\")"
        }
        return if (anyResolved) rewritten else null
    }

    private val URL_REGEX = Regex("""url\(\s*([^)]+?)\s*\)""", RegexOption.IGNORE_CASE)

    /** Common EPUB "package root" directory prefixes probed when a CSS `url()` starts with `/` —
     *  see [rewriteRule]. Ordered by prevalence (older EPUBs use `OEBPS/`, EPUB3 tooling tends
     *  toward `EPUB/`, `content/` shows up on hand-authored books). */
    private val EPUB_ROOT_PREFIXES = listOf("OEBPS/", "EPUB/", "content/")

    private fun resolveAgainst(cssDir: String, rel: String): String {
        if (rel.startsWith('/')) return rel.trimStart('/')
        // Naive `..` collapse: enough for the flat font/ subfolder layout every EPUB in the wild
        // uses. Absolute-path rewriting (drive letters, protocols) is handled by the early
        // `data:` bail-out above and the `startsWith('/')` branch here.
        val parts = (cssDir.split('/') + rel.split('/')).filter { it.isNotEmpty() }
        val stack = ArrayDeque<String>()
        for (p in parts) when (p) {
            "." -> {}
            ".." -> if (stack.isNotEmpty()) stack.removeLast()
            else -> stack.addLast(p)
        }
        return stack.joinToString("/")
    }

    private fun mimeForFontPath(path: String): String {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".woff") -> "font/woff"
            lower.endsWith(".otf") -> "font/otf"
            lower.endsWith(".ttf") -> "font/ttf"
            else -> "application/octet-stream"
        }
    }
}
