package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
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
            assertTrue(
                "Override for ${override.cssProperties} must be gated on :root[style*=\"${override.userPropertyName}\"]",
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
            override.cssProperties.forEach { property ->
                val rule = Regex("${Regex.escape(property)}:\\s*${Regex.escape(override.cssValue)}\\s*!important")
                assertNotNull(
                    "Override for $property must use !important to beat publisher rules without it",
                    rule.find(css),
                )
            }
        }
    }

    // Regression guard: an earlier attempt at applying margins to top/bottom set
    // `elements = "body"`, which silently did nothing because CSS multicol fragments body as a
    // single block — padding-top only shows on the first column, padding-bottom only on the
    // last. The fix targets `:root` (the multicol container) so every page gets equal
    // vertical whitespace. Encoding it as a test so a future refactor doesn't quietly regress.
    @Test
    fun margins_override_targets_root_not_a_descendant() {
        val override = TYPOGRAPHY_OVERRIDES.getValue("margins")
        assertEquals(
            "Margins must pad :root (multicol container); padding on body wouldn't show per-page",
            "", override.elements,
        )
        val css = typographyOverrideCss()
        // The rule for margins must be exactly the gate selector with no descendant —
        // i.e. `:root[style*="--USER__pageMargins"] {`, not `... body {` or similar.
        assertTrue(
            "Margins rule must be scoped to :root itself, not a descendant. CSS was:\n$css",
            css.contains(":root[style*=\"--USER__pageMargins\"] {"),
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
