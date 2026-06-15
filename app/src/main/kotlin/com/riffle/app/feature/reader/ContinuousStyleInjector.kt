package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderTheme

internal object ContinuousStyleInjector {

    /**
     * Produces JS that sets the same `--USER__*` CSS custom properties on `:root` that
     * Readium's `EpubNavigatorFragment` sets via `submitPreferences()`. The null-gating
     * logic mirrors [FormattingPreferencesMapper.toEpubPreferences]: when a value equals
     * the default, Readium passes null — leaving the variable unset so publisher defaults
     * are preserved. We match that behaviour with `removeProperty`.
     *
     * NOTE: verify after any Readium SDK upgrade that the variable names and value formats
     * still match. Run DevTools on a Scroll-mode chapter and compare with the output of
     * this function for the same [FormattingPreferences].
     */
    fun buildVariableInjectionJs(prefs: FormattingPreferences): String {
        val lines = mutableListOf<String>()
        val r = "document.documentElement.style"

        // fontSize — always set (Readium always passes it as a Double)
        lines += "$r.setProperty('--USER__fontSize', '${prefs.fontSize}rem');"

        // lineHeight — null-gated (matches FormattingPreferencesMapper: null when == default)
        if (prefs.lineSpacing != FormattingPreferences.DEFAULT_LINE_SPACING) {
            lines += "$r.setProperty('--USER__lineHeight', '${prefs.lineSpacing}');"
        } else {
            lines += "$r.removeProperty('--USER__lineHeight');"
        }

        // pageMargins — always set
        lines += "$r.setProperty('--USER__pageMargins', '${prefs.margins}');"

        // textAlign — null-gated
        if (prefs.justifyText) {
            lines += "$r.setProperty('--USER__textAlign', 'justify');"
        } else {
            lines += "$r.removeProperty('--USER__textAlign');"
        }

        // fontFamily — null-gated (Serif is default → no variable, matching mapper)
        val fontFamilyValue = when (prefs.fontFamily) {
            ReaderFontFamily.Serif -> null
            ReaderFontFamily.SansSerif -> "sans-serif"
            ReaderFontFamily.Monospace -> "monospace"
            ReaderFontFamily.Literata -> "Literata"
            ReaderFontFamily.Merriweather -> "Merriweather"
            ReaderFontFamily.OpenDyslexic -> "OpenDyslexic"
        }
        if (fontFamilyValue != null) {
            lines += "$r.setProperty('--USER__fontFamily', '$fontFamilyValue');"
        } else {
            lines += "$r.removeProperty('--USER__fontFamily');"
        }

        // textColor — DarkDim only. 0xFFAAAAAA = ReaderThemePalette.DARK_DIM_TEXT
        if (prefs.theme == ReaderTheme.DarkDim) {
            lines += "$r.setProperty('--USER__textColor', '#AAAAAA');"
        } else {
            lines += "$r.removeProperty('--USER__textColor');"
        }

        return lines.joinToString("\n")
    }

    /**
     * JS that fires after fonts load + one rAF and calls `window.RiffleChapter.onHeightMeasured`
     * with `document.body.scrollHeight`. Requires the calling [ChapterWebView] to have
     * registered a `JavascriptInterface` named `RiffleChapter`.
     */
    const val HEIGHT_MEASUREMENT_JS = """
        document.fonts.ready.then(function() {
            requestAnimationFrame(function() {
                window.RiffleChapter.onHeightMeasured(document.body.scrollHeight);
            });
        });
    """

    /**
     * JS that highlights [escapedText] via `window.find` + DOM `<mark>` injection, replacing
     * any existing mark with id `_riffle_hl`. Pass an empty string to clear.
     */
    fun highlightTextJs(escapedText: String): String {
        if (escapedText.isBlank()) return CLEAR_HIGHLIGHT_JS
        return """
            (function() {
                var existing = document.getElementById('_riffle_hl');
                if (existing) { existing.outerHTML = existing.innerHTML; }
                if (!window.find('$escapedText', false, false, false, false, false, false)) return;
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

    const val CLEAR_HIGHLIGHT_JS = """
        (function() {
            var m = document.getElementById('_riffle_hl');
            if (m) m.outerHTML = m.innerHTML;
        })();
    """
}
