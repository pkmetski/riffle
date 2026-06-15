package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderTheme

internal object ContinuousStyleInjector {

    /**
     * Produces JS that injects a `<style id="_riffle_user">` element with real CSS properties
     * derived from [prefs]. Unlike the `--USER__*` Readium CSS-variable approach, this works
     * without ReadiumCSS being loaded — ChapterWebViews serve raw EPUB HTML and do not have
     * Readium's stylesheet present.
     *
     * Colours mirror [ReaderThemePalette]; keep in sync if the palette values change.
     * Font-size is set on `html` so EPUB content that uses `em`/`rem` scales correctly.
     * Margins are expressed as body padding (percent of viewport width).
     * All rules carry `!important` to beat typical publisher CSS specificity.
     */
    fun buildStyleInjectionJs(prefs: FormattingPreferences): String {
        val css = buildCss(prefs)
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
        return """
            (function() {
                var s = document.getElementById('_riffle_user');
                if (!s) {
                    s = document.createElement('style');
                    s.id = '_riffle_user';
                    document.head.appendChild(s);
                }
                s.textContent = '$css';
            })();
        """.trimIndent()
    }

    /**
     * URL prefix under which we serve the app's bundled fonts from [ChapterWebView].
     * These paths are intercepted in `shouldInterceptRequest` and served from Android assets.
     */
    private const val FONT_BASE = "https://readium_package/readium/fonts"

    internal fun buildCss(prefs: FormattingPreferences): String {
        // Theme colours — mirrors ReaderThemePalette (keep in sync).
        val bg = when (prefs.theme) {
            ReaderTheme.Dark, ReaderTheme.DarkDim -> "#000000"
            ReaderTheme.Sepia -> "#FAF4E8"
            else -> null
        }
        val fg = when (prefs.theme) {
            ReaderTheme.Dark -> "#FEFEFE"
            ReaderTheme.DarkDim -> "#AAAAAA"
            ReaderTheme.Sepia -> "#121212"
            else -> null
        }

        // Font family mapping mirrors FormattingPreferencesMapper.toEpubPreferences():
        //   Serif      → null (Readium default) → system generic `serif`
        //   SansSerif  → FontFamily("sans-serif") → system generic `sans-serif`
        //   Monospace  → FontFamily("monospace")  → system generic `monospace`
        //   Literata/Merriweather/OpenDyslexic → app-bundled fonts served from assets via
        //     shouldInterceptRequest at FONT_BASE; declared here with @font-face.
        val fontFamily: String = when (prefs.fontFamily) {
            ReaderFontFamily.Serif -> "serif"
            ReaderFontFamily.SansSerif -> "sans-serif"
            ReaderFontFamily.Monospace -> "monospace"
            ReaderFontFamily.Literata -> "Literata, serif"
            ReaderFontFamily.Merriweather -> "Merriweather, serif"
            ReaderFontFamily.OpenDyslexic -> "OpenDyslexic, sans-serif"
        }

        // Include @font-face declarations for bundled fonts whenever one of them is active.
        // The font files are served by ChapterWebView.shouldInterceptRequest from Android assets.
        val needsBundledFonts = prefs.fontFamily in setOf(
            ReaderFontFamily.Literata,
            ReaderFontFamily.Merriweather,
            ReaderFontFamily.OpenDyslexic,
        )

        val textAlign = if (prefs.justifyText) "justify" else "left"
        // margins: 1.0 = normal ≈ 6 % per side (matches Readium's --RS__pageGutter × pageMargins).
        val paddingPct = (prefs.margins * 6f).toInt().coerceIn(1, 14)

        return buildString {
            // @font-face declarations must come before any rule that references the font name.
            if (needsBundledFonts) {
                append("@font-face{font-family:Literata;font-style:normal;font-weight:400;")
                append("src:url('$FONT_BASE/Literata-Regular.ttf') format('truetype');}\n")
                append("@font-face{font-family:Merriweather;font-style:normal;font-weight:400;")
                append("src:url('$FONT_BASE/Merriweather-Regular.ttf') format('truetype');}\n")
                append("@font-face{font-family:OpenDyslexic;font-style:normal;font-weight:400;")
                append("src:url('$FONT_BASE/OpenDyslexic-Regular.otf') format('opentype');}\n")
            }

            // ReadiumCSS sets -webkit-text-size-adjust:100% on :root to prevent Chrome's
            // "font inflation" feature from boosting small text on mobile. Without it,
            // Continuous mode can render text at a different size than Scroll/Paginated mode.
            append("html{")
            if (bg != null) append("background-color:$bg!important;")
            if (fg != null) append("color:$fg!important;")
            // font-size on html so em/rem-based EPUB content scales with the user preference.
            append("font-size:${prefs.fontSize}rem!important;")
            append("-webkit-text-size-adjust:100%!important;text-size-adjust:100%!important;")
            append("}\n")

            append("body{")
            if (bg != null) append("background-color:$bg!important;")
            if (fg != null) append("color:$fg!important;")
            append("font-family:$fontFamily!important;")
            append("line-height:${prefs.lineSpacing}!important;")
            append("text-align:$textAlign!important;")
            append("padding-left:${paddingPct}%!important;padding-right:${paddingPct}%!important;")
            // Zero out all body margin/vertical-padding so the default 8px browser body margin
            // doesn't appear as a visible gap at every chapter boundary in the stacked layout.
            append("margin:0!important;padding-top:0!important;padding-bottom:0!important;")
            append("}\n")

            // ReadiumCSS advanced mode (publisherStyles=false) sets font-size:1rem!important on
            // content elements to strip EPUB per-element font-size overrides. Without this,
            // EPUB rules like `p { font-size: 0.9em }` make paragraphs smaller in Continuous
            // mode than in Scroll/Paginated mode where ReadiumCSS overrides them.
            append("p,li,dd,div{")
            append("font-size:1rem!important;")
            append("}\n")

            // Push text-align and line-height down to the elements publishers commonly override.
            append("p,li,blockquote,dd,dt,figcaption{")
            append("text-align:$textAlign!important;")
            append("line-height:${prefs.lineSpacing}!important;")
            append("}\n")

            append("p,li,blockquote,dd,dt,h1,h2,h3,h4,h5,h6,figcaption{")
            append("font-family:$fontFamily!important;")
            append("}\n")
        }
    }

    /**
     * JS that calls `window.RiffleChapter.onHeightMeasured` with the chapter's CSS-pixel
     * scroll height once fonts have settled. A `fired` guard prevents the double-call that
     * would otherwise occur when both `document.fonts.ready` and the 500 ms safety timeout
     * resolve — on modern Chrome both always fire, and a second `onHeightMeasured` can cause
     * a spurious layout change or scrollBy in the parent [ContinuousReaderView].
     */
    val HEIGHT_MEASUREMENT_JS = """
        (function() {
            var fired = false;
            function measure() {
                if (fired) return;
                fired = true;
                var h = document.body.scrollHeight;
                if (h > 0) window.RiffleChapter.onHeightMeasured(h);
            }
            if (document.fonts && document.fonts.ready) {
                document.fonts.ready.then(function() { requestAnimationFrame(measure); });
            }
            setTimeout(measure, 500);
        })();
    """.trimIndent()

    /**
     * JS that forwards single taps on the document body to `window.RiffleChapter.onTap` so the
     * Continuous-mode reader chrome (top/bottom bars) can toggle on tap, matching the standard
     * Readium navigator's [InputListener.onTap] behaviour. The listener is idempotent.
     */
    val TAP_LISTENER_JS = """
        (function() {
            if (document.__riffleTapWired) return;
            document.__riffleTapWired = true;
            document.addEventListener('click', function() {
                window.RiffleChapter.onTap();
            }, false);
        })();
    """.trimIndent()

    val CLEAR_HIGHLIGHT_JS = """
        (function() {
            var m = document.getElementById('_riffle_hl');
            if (m) m.outerHTML = m.innerHTML;
        })();
    """.trimIndent()

    /**
     * JS that highlights [text] via `window.find` + DOM `<mark>` injection, replacing
     * any existing mark with id `_riffle_hl`. Pass an empty string to clear.
     * Single quotes and backslashes in [text] are escaped before embedding in JS.
     */
    fun highlightTextJs(text: String): String {
        if (text.isBlank()) return CLEAR_HIGHLIGHT_JS
        val safe = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
        return """
            (function() {
                var existing = document.getElementById('_riffle_hl');
                if (existing) { existing.outerHTML = existing.innerHTML; }
                if (!window.find('$safe', false, false, false, false, false, false)) return;
                var sel = window.getSelection();
                if (!sel || sel.rangeCount === 0) return;
                var range = sel.getRangeAt(0);
                var mark = document.createElement('mark');
                mark.id = '_riffle_hl';
                mark.style.cssText = 'background:#7DD3FC;color:inherit;';
                try { range.surroundContents(mark); } catch(e) {}
                sel.removeAllRanges();
            })();
        """.trimIndent()
    }
}
