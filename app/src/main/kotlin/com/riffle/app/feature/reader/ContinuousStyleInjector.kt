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

        // textColor — DarkDim only. #AAAAAA = ReaderThemePalette.DARK_DIM_TEXT; keep in sync if the palette changes
        if (prefs.theme == ReaderTheme.DarkDim) {
            lines += "$r.setProperty('--USER__textColor', '#AAAAAA');"
        } else {
            lines += "$r.removeProperty('--USER__textColor');"
        }

        // backgroundColor — theme-dependent; mirrors --RS__backgroundColor from Readium's CSS
        when (prefs.theme) {
            ReaderTheme.Dark, ReaderTheme.DarkDim -> {
                lines += "$r.setProperty('--USER__backgroundColor', '#000000');"
            }
            ReaderTheme.Sepia -> {
                // #FAF4E8 = ReaderThemePalette.Sepia.background; keep in sync if the palette changes
                lines += "$r.setProperty('--USER__backgroundColor', '#FAF4E8');"
            }
            else -> {
                lines += "$r.removeProperty('--USER__backgroundColor');"
            }
        }

        return lines.joinToString("\n")
    }

    /**
     * JS that calls `window.RiffleChapter.onHeightMeasured` with the chapter height in device
     * pixels. Uses `window.devicePixelRatio` to convert from CSS pixels so `LayoutParams.height`
     * receives the correct device-pixel value regardless of whether the EPUB has a viewport meta
     * tag. `document.fonts.ready` is tried first; a 500 ms timeout fires as a fallback in case
     * the promise never resolves (older WebViews or EPUBs with failing font loads).
     */
    val HEIGHT_MEASUREMENT_JS = """
        (function() {
            function measure() {
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
