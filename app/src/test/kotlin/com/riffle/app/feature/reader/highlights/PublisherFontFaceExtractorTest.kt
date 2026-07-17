package com.riffle.app.feature.reader.highlights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `@font-face` extraction + rewrite pipeline used to embed the source EPUB's fonts into
 * the synthesised elided-view HTML (elided-view-serif-font-regression follow-up).
 *
 * Regression this file prevents: without the extractor, the elided view emits
 * `font-family: Nimbusromno9l;` on `<body>` but no `@font-face` for that face — the WebView can't
 * resolve it and falls back to a generic system serif that visibly diverges from the source
 * reader. The tests cover the two subtle failure modes: (a) an unresolved `url(...)` in the CSS
 * silently drops the whole rule (better than emitting a broken `@font-face` some WebViews treat
 * as "font unusable" and refuse to fall back), and (b) `url()` paths are resolved against the
 * CSS file's own directory before hitting the resolver (so an EPUB whose CSS lives in `OEBPS/`
 * and references `font_rsrc2H2.ttf` correctly asks for `OEBPS/font_rsrc2H2.ttf`).
 */
class PublisherFontFaceExtractorTest {

    @Test
    fun rewritesRelativeUrlToBase64DataUri() {
        val css = """
            @font-face {
              font-family: 'Nimbusromno9l';
              src: url('font_rsrc2H2.ttf') format('truetype');
              font-weight: normal;
            }
        """.trimIndent()
        val fontBytes = byteArrayOf(1, 2, 3, 4, 5)
        val result = PublisherFontFaceExtractor.extract(
            cssFiles = listOf("OEBPS/stylesheet.css" to css.toByteArray()),
            fontResolver = { path ->
                if (path == "OEBPS/font_rsrc2H2.ttf") fontBytes else null
            },
        )
        assertTrue(
            "expected inlined data URI in output, got: $result",
            result.contains("data:font/ttf;base64,AQIDBAU="),
        )
        assertTrue("expected font-family preserved, got: $result", result.contains("Nimbusromno9l"))
    }

    @Test
    fun dropsRuleWhenReferencedFontCannotBeResolved() {
        val css = """
            @font-face {
              font-family: 'GhostFont';
              src: url('missing.ttf');
            }
        """.trimIndent()
        val result = PublisherFontFaceExtractor.extract(
            cssFiles = listOf("stylesheet.css" to css.toByteArray()),
            fontResolver = { null },
        )
        // Rule with only unresolvable url()s must be dropped — an emitted `@font-face` with a
        // broken `src` makes some WebViews mark the family as "unusable" and skip the
        // system-serif fallback we'd otherwise get.
        assertEquals("", result.trim())
    }

    @Test
    fun ignoresNonFontFaceCssRulesAndBlockComments() {
        val css = """
            /* @font-face { src: url(fake.ttf); }  ← must be ignored */
            body { color: red; }
            @font-face {
              font-family: 'Real';
              src: url(font.ttf);
            }
            .other { font-family: 'Real'; }
        """.trimIndent()
        val result = PublisherFontFaceExtractor.extract(
            cssFiles = listOf("s.css" to css.toByteArray()),
            fontResolver = { if (it == "font.ttf") byteArrayOf(9, 9) else null },
        )
        assertTrue(result.contains("'Real'"))
        // Commented-out rule must not leak into the output.
        assertFalse(
            "commented-out @font-face must not be emitted; got: $result",
            result.contains("fake.ttf"),
        )
        // Non-@font-face rules must not be echoed back.
        assertFalse(
            "non-@font-face declarations must not be echoed; got: $result",
            result.contains("color: red") || result.contains(".other"),
        )
    }

    @Test
    fun resolvesUrlAgainstCssDirectoryBeforeFallingBackToRawPath() {
        val css = """
            @font-face { font-family: 'A'; src: url('fonts/a.ttf'); }
        """.trimIndent()
        val calls = mutableListOf<String>()
        PublisherFontFaceExtractor.extract(
            cssFiles = listOf("OEBPS/css/stylesheet.css" to css.toByteArray()),
            fontResolver = { path ->
                calls += path
                if (path == "OEBPS/css/fonts/a.ttf") byteArrayOf(0) else null
            },
        )
        assertTrue(
            "expected CSS-dir-resolved path (OEBPS/css/fonts/a.ttf) to be asked first; calls=$calls",
            calls.firstOrNull() == "OEBPS/css/fonts/a.ttf",
        )
    }

    @Test
    fun preservesExistingDataUriUrlsUnchanged() {
        val css = """
            @font-face {
              font-family: 'AlreadyInline';
              src: url('data:font/ttf;base64,AAAA');
            }
        """.trimIndent()
        val result = PublisherFontFaceExtractor.extract(
            cssFiles = listOf("s.css" to css.toByteArray()),
            fontResolver = { error("resolver must NOT be consulted for existing data: URIs") },
        )
        // A CSS already carrying its fonts inline is a legitimate publisher pattern
        // (uncommon but permitted); the extractor must pass such rules through untouched
        // rather than mark them as unresolved and drop them.
        assertTrue(result.contains("data:font/ttf;base64,AAAA"))
    }

    // Regression (review finding): a CSS `@font-face` with `src: url('/fonts/x.ttf')` (leading
    // slash means "relative to the EPUB package root") must find the font under the EPUB's
    // package directory (typically `OEBPS/`), not the ZIP root. Without the package-root
    // fallback the extractor drops the rule and the elided view falls through to a generic
    // serif — the exact class of miss the whole change is guarding against.
    @Test
    fun resolvesLeadingSlashUrlAgainstEpubPackageRootPrefixes() {
        val css = """
            @font-face { font-family: 'A'; src: url('/fonts/a.ttf'); }
        """.trimIndent()
        val result = PublisherFontFaceExtractor.extract(
            cssFiles = listOf("OEBPS/css/stylesheet.css" to css.toByteArray()),
            fontResolver = { path ->
                // Only the OEBPS/-prefixed path resolves — mirroring the real ZIP layout.
                if (path == "OEBPS/fonts/a.ttf") byteArrayOf(7, 7, 7) else null
            },
        )
        assertTrue(
            "expected @font-face rule to survive via OEBPS/-prefix fallback; got: $result",
            result.contains("data:font/ttf;base64,BwcH"),
        )
    }

    @Test
    fun emitsEmptyStringWhenNoFontFaceRulesInAnyCssFile() {
        val css = "body { color: black; } p { margin: 0; }"
        val result = PublisherFontFaceExtractor.extract(
            cssFiles = listOf("s.css" to css.toByteArray()),
            fontResolver = { null },
        )
        assertEquals(
            "no @font-face → empty output → factory omits the <style> block entirely",
            "",
            result,
        )
    }
}
