package com.riffle.app.feature.reader.formatting

import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderTheme

// %-of-viewport heuristic: FormattingPreferences.margins ranges roughly 0.2-3.0, the same
// slider used by the EPUB margin control; we reuse that value directly as a percentage of
// viewer width so the gutter grows in step with the EPUB margin setting.
private const val MARGIN_MIN = 0.2f
private const val MARGIN_MAX = 3.0f

fun buildPdfFormattingCss(prefs: FormattingPreferences): String {
    val bg = when (prefs.theme) {
        ReaderTheme.Light -> "#FFFFFF"
        ReaderTheme.Dark -> "#000000"
        ReaderTheme.DarkDim -> "#121212"
        ReaderTheme.Sepia -> "#F4ECD8"
        ReaderTheme.Auto -> "var(--riffle-auto-bg)"
    }
    val marginPct = prefs.margins.coerceIn(MARGIN_MIN, MARGIN_MAX)
    return """
        (function() {
          const root = document.documentElement;
          root.style.setProperty('--pdf-bg', '$bg');
          const viewer = document.getElementById('viewerContainer');
          if (viewer) {
            viewer.style.setProperty('padding-left', '${marginPct}%');
            viewer.style.setProperty('padding-right', '${marginPct}%');
          }
          document.body.style.backgroundColor = '$bg';
        })();
    """.trimIndent()
}
