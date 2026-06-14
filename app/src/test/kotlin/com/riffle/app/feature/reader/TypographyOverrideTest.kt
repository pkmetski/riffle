package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypographyOverrideTest {

    // Reflective field discovery via java.lang.reflect — avoids the kotlin-reflect dependency.
    // For a Kotlin data class, the constructor parameters become instance fields with matching
    // names. We filter out companion-object constants (static fields), synthetics, and the
    // generated `Companion` reference field.
    private val formattingPreferenceFieldNames: Set<String> =
        FormattingPreferences::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    // Contract: when a new field is added to FormattingPreferences, the maintainer must
    // classify it as either an overridable typography knob (TYPOGRAPHY_OVERRIDES) or as
    // deliberately excluded with a reason (EXCLUDED_FROM_TYPOGRAPHY_OVERRIDES). Forgetting
    // to classify a field means the typography-override system silently doesn't apply to it
    // and the bug we just fixed (line-spacing not applying on certain books) would silently
    // recur for the new field. This test fails loudly on omission.
    @Test
    fun every_formatting_preferences_field_is_either_overridable_or_excluded() {
        val classified = TYPOGRAPHY_OVERRIDES.keys + EXCLUDED_FROM_TYPOGRAPHY_OVERRIDES.keys
        val unclassified = formattingPreferenceFieldNames - classified
        assertEquals(
            "Every FormattingPreferences field must be classified in TYPOGRAPHY_OVERRIDES " +
                "or EXCLUDED_FROM_TYPOGRAPHY_OVERRIDES. Missing classifications: $unclassified",
            emptySet<String>(),
            unclassified,
        )
    }

    @Test
    fun classification_maps_only_reference_real_formatting_preferences_fields() {
        val classified = TYPOGRAPHY_OVERRIDES.keys + EXCLUDED_FROM_TYPOGRAPHY_OVERRIDES.keys
        val mystery = classified - formattingPreferenceFieldNames
        assertEquals(
            "Classification maps reference non-existent fields: $mystery (likely a typo or " +
                "a field that was removed from FormattingPreferences).",
            emptySet<String>(),
            mystery,
        )
    }

    @Test
    fun no_field_is_both_overridden_and_excluded() {
        val both = TYPOGRAPHY_OVERRIDES.keys intersect EXCLUDED_FROM_TYPOGRAPHY_OVERRIDES.keys
        assertEquals(emptySet<String>(), both)
    }

    @Test
    fun every_excluded_field_has_a_non_blank_reason() {
        val blank = EXCLUDED_FROM_TYPOGRAPHY_OVERRIDES.filterValues { it.isBlank() }.keys
        assertEquals(
            "Excluded fields must document why they are excluded: $blank",
            emptySet<String>(),
            blank,
        )
    }

    // The override CSS must gate each rule on the variable's presence on :root. Without the
    // gate, the override would apply universally — including to books the user hasn't
    // customised — overriding the publisher's typography unconditionally.
    @Test
    fun every_override_rule_is_gated_on_root_style_variable_presence() {
        val css = typographyOverrideCss()
        TYPOGRAPHY_OVERRIDES.values.forEach { override ->
            val props = override.declarations.joinToString(", ") { it.first }
            assertTrue(
                "Override for $props must be gated on :root[style*=\"${override.userPropertyName}\"]",
                css.contains(":root[style*=\"${override.userPropertyName}\"]"),
            )
        }
    }

    // Without !important, hostile publisher rules like `#sbo-rt-content p { ... }` (0,1,1)
    // would beat our (0,1,1) gate+element selectors on source order alone. !important keeps
    // us ahead regardless of where the publisher's stylesheet sits in the cascade.
    @Test
    fun every_override_uses_important() {
        val css = typographyOverrideCss()
        TYPOGRAPHY_OVERRIDES.values.forEach { override ->
            override.declarations.forEach { (property, value) ->
                val rule = Regex("${Regex.escape(property)}:\\s*${Regex.escape(value)}\\s*!important")
                assertNotNull(
                    "Override for $property must use !important to beat publisher rules without it",
                    rule.find(css),
                )
            }
        }
    }

    // No margins typography override: the page's top/bottom margin is owned by Readium's pre-paint
    // layout (native container vertical padding), not a :root padding injected in onPageLoaded. An
    // injected :root padding lands after first paint and visibly dropped the page on every chapter
    // start (the "page falls from above" bug), so `margins` is intentionally excluded.
    @Test
    fun margins_has_no_post_paint_root_padding_override() {
        assertFalse(TYPOGRAPHY_OVERRIDES.containsKey("margins"))
        assertTrue(EXCLUDED_FROM_TYPOGRAPHY_OVERRIDES.containsKey("margins"))
        // No injected rule may pad :root for the page margin (that is the reflow that caused the fall).
        assertFalse(typographyOverrideCss().contains("padding-top"))
    }

    // Regression guard: text-align is the one user property where ReadiumCSS's own rule uses
    // `text-align: inherit !important` on `p, li, body` at specificity (0,2,4). With a single
    // `[style*=…]` gate our rule lands at (0,1,2) and loses — so `p` inherits from its nearest
    // div ancestor, and publisher CSS like Safari Books Online's
    // `#sbo-rt-content div { text-align: left }` (1,0,1) wins on that div, leaving paragraphs
    // wrapped in semantic divs (blockquotes, sidebars, list items, callouts) left-aligned even
    // when the user has chosen justify. The bumped reps push us to (0,3,2) which beats Readium.
    @Test
    fun justify_text_override_has_enough_specificity_to_beat_readium_inherit_rule() {
        val override = TYPOGRAPHY_OVERRIDES.getValue("justifyText")
        assertTrue(
            "justifyText must repeat its gate attribute selector at least 3 times to beat " +
                "Readium's (0,2,4) `text-align: inherit !important` rule on p/li/body. " +
                "Lowering this will silently regress justify on O'Reilly / Safari Books Online " +
                "EPUBs and any publisher whose CSS sets text-align on div ancestors of p.",
            override.gateAttrReps >= 3,
        )
        val css = typographyOverrideCss()
        // The selector must contain three back-to-back [style*=…] attribute selectors on :root.
        assertTrue(
            "Generated CSS must contain :root[style*=\"--USER__textAlign\"][style*=\"--USER__textAlign\"][style*=\"--USER__textAlign\"]. CSS was:\n$css",
            css.contains(
                ":root" +
                    "[style*=\"--USER__textAlign\"]".repeat(3),
            ),
        )
    }

    // Injection JS must be idempotent — onPageLoaded fires more than once per page during
    // reflow, and naive injection would accumulate <style> tags.
    @Test
    fun injection_js_is_idempotent_via_stable_id() {
        val js = typographyOverrideInjectionJs()
        assertTrue("Injection JS must check for an existing element by id before appending", js.contains("getElementById"))
        assertTrue("Injection JS must use a stable id", js.contains("riffle-typography-override"))
    }
}
