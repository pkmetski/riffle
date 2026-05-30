package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences

/**
 * Targeted typography overrides for EPUBs whose publisher CSS uses high-specificity selectors
 * (e.g. Safari Books Online's `#sbo-rt-content p { line-height: 125% }`) that beat Readium's
 * own `body`-level user-property rules.
 *
 * Strategy: inject a stylesheet that, for each user-controlled CSS property, asserts the
 * `--USER__*` variable with `!important` on the elements where the publisher is likely to
 * win the cascade. The rule is gated on `:root[style*="--USER__name"]` so it only fires
 * when the user has actually customised the property — see [FormattingPreferencesMapper] for
 * the corresponding null-gating that ensures Readium leaves the variable unset on default
 * values, which keeps publisher typography intact on uncustomised books.
 *
 * Specificity caveat: this beats publisher rules that lack `!important`. A hostile EPUB that
 * combines high-specificity selectors *with* `!important` would still win — handle that with
 * a JS-applied inline-style escape hatch if it ever shows up in the wild.
 */
internal data class TypographyOverride(
    val userPropertyName: String,
    val cssProperty: String,
    val elements: String,
)

/**
 * Maps a [FormattingPreferences] field name to the targeted override that enforces it.
 * The contract test in `TypographyOverrideTest` verifies every field of
 * [FormattingPreferences] is either present here or in [EXCLUDED_FROM_TYPOGRAPHY_OVERRIDES].
 */
internal val TYPOGRAPHY_OVERRIDES: Map<String, TypographyOverride> = mapOf(
    "lineSpacing" to TypographyOverride(
        userPropertyName = "--USER__lineHeight",
        cssProperty = "line-height",
        // Headings deliberately excluded — books style heading line-height tightly (often 1.0)
        // and forcing the body line-height onto them looks wrong.
        elements = "p, li, blockquote, dd, dt, figcaption",
    ),
    "justifyText" to TypographyOverride(
        userPropertyName = "--USER__textAlign",
        cssProperty = "text-align",
        elements = "p, li, blockquote, dd",
    ),
    "fontFamily" to TypographyOverride(
        userPropertyName = "--USER__fontFamily",
        cssProperty = "font-family",
        // `pre`/`code`/`kbd`/`samp` deliberately excluded so monospace code keeps its font.
        // Headings included so picking a reading font feels consistent across body and titles.
        elements = "body, p, li, blockquote, dd, dt, h1, h2, h3, h4, h5, h6, figcaption",
    ),
)

/**
 * Fields of [FormattingPreferences] that have no targeted typography override, with the
 * reason. The contract test asserts every excluded field has a documented reason so future
 * maintainers don't accidentally add a field to this list as a way of silencing the test.
 */
internal val EXCLUDED_FROM_TYPOGRAPHY_OVERRIDES: Map<String, String> = mapOf(
    "fontSize" to "Applied via root-em multiplier; a per-element override would flatten the publisher's size hierarchy.",
    "margins" to "Implemented by Readium as container width, not a CSS property on text elements.",
    "theme" to "Multi-property (background, text colour, link colour, image filters) — handled by Readium's theme stylesheet, not a single override.",
    "orientation" to "Layout/scroll mode, not a CSS property.",
    "doublePageSpread" to "Column-count layout mode, handled by RsProperties at fragment-creation time.",
    "showChapterMap" to "UI affordance outside the reader content; no CSS implication.",
)

/**
 * Produces the CSS injected into every reflowable EPUB resource. Idempotent and pure — the
 * caller is responsible for inserting it as a `<style>` element with a stable id so repeated
 * injections don't accumulate.
 *
 * Note: `:where()` would keep selector specificity tidy but is unsupported on Chromium <88
 * (Jan 2021), which means it fails on the WebView shipped with the Android 7.1.1 harness
 * AVD — the whole rule gets rejected as invalid and the override silently does nothing. We
 * emit a plain comma-expanded selector list instead; specificity ends up at (0,1,1) per
 * selector which is enough to beat hostile publisher rules paired with `!important`.
 */
internal fun typographyOverrideCss(): String =
    TYPOGRAPHY_OVERRIDES.values.joinToString("\n") { override ->
        val gate = """:root[style*="${override.userPropertyName}"]"""
        val selectorList = override.elements
            .split(",")
            .joinToString(",\n") { "$gate ${it.trim()}" }
        """
        $selectorList {
          ${override.cssProperty}: var(${override.userPropertyName}) !important;
        }
        """.trimIndent()
    }

/**
 * JavaScript that idempotently injects [typographyOverrideCss] into `document.head`.
 * Safe to evaluate multiple times — the stable id prevents duplicate `<style>` elements.
 */
internal fun typographyOverrideInjectionJs(): String {
    val css = typographyOverrideCss()
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("\$", "\\\$")
    return """
        (function() {
          var id = 'riffle-typography-override';
          if (document.getElementById(id)) return;
          var style = document.createElement('style');
          style.id = id;
          style.textContent = `$css`;
          document.head.appendChild(style);
        })();
    """.trimIndent()
}
