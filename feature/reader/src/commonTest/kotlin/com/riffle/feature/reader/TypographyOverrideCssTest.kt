package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TypographyOverrideCssTest {

    // The override CSS must gate each rule on the variable's presence on :root. Without the
    // gate, the override would apply universally — including to books the user hasn't
    // customised — overriding the publisher's typography unconditionally.
    @Test
    fun every_override_rule_is_gated_on_root_style_variable_presence() {
        val css = typographyOverrideCss()
        TYPOGRAPHY_OVERRIDES.values.forEach { override ->
            val props = override.declarations.joinToString(", ") { it.first }
            assertTrue(
                css.contains(":root[style*=\"${override.userPropertyName}\"]"),
                "Override for $props must be gated on :root[style*=\"${override.userPropertyName}\"]",
            )
        }
    }

    // Without !important, hostile publisher rules like `#sbo-rt-content p { ... }` (0,1,1)
    // would beat our (0,1,1) gate+element selectors on source order alone.
    @Test
    fun every_override_uses_important() {
        val css = typographyOverrideCss()
        TYPOGRAPHY_OVERRIDES.values.forEach { override ->
            override.declarations.forEach { (property, value) ->
                val rule = Regex("${Regex.escape(property)}:\\s*${Regex.escape(value)}\\s*!important")
                assertNotNull(
                    rule.find(css),
                    "Override for $property must use !important",
                )
            }
        }
    }

    // No margins typography override: an injected :root padding lands after first paint and
    // visibly drops the page on every chapter start (the "page falls from above" bug).
    @Test
    fun margins_has_no_post_paint_root_padding_override() {
        assertFalse(TYPOGRAPHY_OVERRIDES.containsKey("margins"))
        assertFalse(typographyOverrideCss().contains("padding-top"))
    }

    // Regression guard: text-align is the one user property where ReadiumCSS's own rule uses
    // `text-align: inherit !important` on `p, li, body` at specificity (0,2,4). With a single
    // `[style*=…]` gate our rule lands at (0,1,2) and loses — so `p` inherits from its nearest
    // div ancestor, and publisher CSS like Safari Books Online's
    // `#sbo-rt-content div { text-align: left }` (1,0,1) wins on that div, leaving paragraphs
    // wrapped in semantic divs left-aligned even when the user has chosen justify. The bumped
    // reps push us to (0,3,2) which beats Readium.
    @Test
    fun justify_text_override_has_enough_specificity_to_beat_readium_inherit_rule() {
        val override = TYPOGRAPHY_OVERRIDES.getValue("justifyText")
        assertTrue(
            override.gateAttrReps >= 3,
            "justifyText must repeat its gate attribute selector at least 3 times to beat " +
                "Readium's (0,2,4) `text-align: inherit !important` rule on p/li/body.",
        )
        val css = typographyOverrideCss()
        assertTrue(
            css.contains(
                ":root" + "[style*=\"--USER__textAlign\"]".repeat(3),
            ),
            "Generated CSS must contain :root[style*=\"--USER__textAlign\"] x3.",
        )
    }

    // Injection JS must be idempotent — page-load fires more than once per page during
    // reflow, and naive injection would accumulate <style> tags.
    @Test
    fun install_script_is_idempotent_via_stable_id() {
        val js = TYPOGRAPHY_OVERRIDE_INSTALL_SCRIPT
        assertTrue(js.contains("getElementById"), "Install script must check for an existing element by id before appending")
        assertTrue(js.contains("riffle-typography-override"), "Install script must use a stable id")
    }
}
