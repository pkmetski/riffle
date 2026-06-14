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
    // Each (property, value) pair becomes one CSS declaration with !important. List of pairs
    // (not Map) because order matters in CSS and a property could appear twice if ever needed.
    val declarations: List<Pair<String, String>>,
    val elements: String,
    // Number of times to repeat the `[style*="…"]` attribute selector on the `:root` gate.
    // Each repetition adds (0,1,0) to specificity. Bump above 1 when Readium's own ReadiumCSS
    // rule for the same property targets the element with higher specificity AND `!important`
    // — in which case matching specificity is the only way to win the cascade.
    val gateAttrReps: Int = 1,
)

/**
 * Maps a [FormattingPreferences] field name to the targeted override that enforces it.
 * The contract test in `TypographyOverrideTest` verifies every field of
 * [FormattingPreferences] is either present here or in [EXCLUDED_FROM_TYPOGRAPHY_OVERRIDES].
 */
internal val TYPOGRAPHY_OVERRIDES: Map<String, TypographyOverride> = mapOf(
    "lineSpacing" to TypographyOverride(
        userPropertyName = "--USER__lineHeight",
        declarations = listOf("line-height" to "var(--USER__lineHeight)"),
        // Headings deliberately excluded — books style heading line-height tightly (often 1.0)
        // and forcing the body line-height onto them looks wrong.
        elements = "p, li, blockquote, dd, dt, figcaption",
    ),
    "justifyText" to TypographyOverride(
        userPropertyName = "--USER__textAlign",
        declarations = listOf("text-align" to "var(--USER__textAlign)"),
        elements = "p, li, blockquote, dd",
        // Readium's after.css forces `text-align: inherit !important` on `p, li, body` with
        // selector `:root[a][b] :not(blockquote):not(figcaption) p` (specificity 0,2,4). With
        // a single `[style*=…]` we'd land at (0,1,2) and lose, leaving `p` inheriting from
        // its nearest div ancestor — which for publisher CSS like Safari Books Online's
        // `#sbo-rt-content div { text-align: left }` (1,0,1, non-important) breaks justify
        // on everything wrapped in a semantic div (blockquotes, sidebars, list items,
        // callouts). 3 attribute reps → (0,3,2), beating Readium on the second component.
        gateAttrReps = 3,
    ),
    "fontFamily" to TypographyOverride(
        userPropertyName = "--USER__fontFamily",
        declarations = listOf("font-family" to "var(--USER__fontFamily)"),
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
    "margins" to "Page margins come from Readium's pre-paint layout: left/right from its built-in body " +
        "padding (scales with --USER__pageMargins), top/bottom from its native container vertical padding " +
        "(readium_navigator_epub_vertical_padding). We deliberately do NOT inject a :root padding override " +
        "for the top/bottom margin: injected in onPageLoaded it lands AFTER first paint, so the page visibly " +
        "dropped by the margin amount on every chapter start (the 'page falls from above' bug). Letting " +
        "Readium own the vertical margin keeps it applied before the page is revealed, with no reflow.",
    "fontSize" to "Applied via root-em multiplier; a per-element override would flatten the publisher's size hierarchy.",
    "theme" to "Multi-property (background, text colour, link colour, image filters) — handled by Readium's theme stylesheet, not a single override.",
    "orientation" to "Layout/scroll mode, not a CSS property.",
    "doublePageSpread" to "Column-count layout mode, handled by RsProperties at fragment-creation time.",
    "showChapterMap" to "UI affordance outside the reader content; no CSS implication.",
    "showReadingProgressLabels" to "UI affordance outside the reader content; no CSS implication.",
    "showCurrentChapterLabel" to "UI affordance outside the reader content; no CSS implication.",
    "themeSchedule" to "Schedule metadata used to derive the resolved theme at runtime; has no direct CSS implication.",
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
        val gate = """:root""" +
            """[style*="${override.userPropertyName}"]""".repeat(override.gateAttrReps)
        // Empty `elements` means the rule targets `:root` itself (used for margins, which
        // pads the multicol container so every page gets top/bottom whitespace).
        val selectorList = if (override.elements.isBlank()) {
            gate
        } else {
            override.elements
                .split(",")
                .joinToString(",\n") { "$gate ${it.trim()}" }
        }
        val declarations = override.declarations
            .joinToString("\n          ") { (prop, value) -> "$prop: $value !important;" }
        """
        $selectorList {
          $declarations
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
