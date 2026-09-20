package com.riffle.shared.reader

import com.riffle.core.models.HighlightColor

/**
 * The tint a highlight decoration falls back to when the bridge payload omits `color`/`alpha`.
 *
 * Exists so the Swift side has no colour literal of its own. Its previous fallback, `"#FFFF00"`
 * at alpha `0.4`, had silently drifted away from [HighlightColor.DEFAULT] — it read as correct in
 * review while painting a colour the palette does not contain. This is the same
 * reference-the-constant rule the rest of the palette follows: to change the default, edit
 * `HighlightColor` and nothing else.
 *
 * Dropping the decoration instead of falling back was considered and rejected: a payload that
 * loses a field would make the user's highlight vanish from the page, which is strictly worse
 * than rendering it in the palette's default tint.
 */
object ReaderHighlightDefaults {
    /** `#RRGGBB` of [HighlightColor.DEFAULT]. */
    val highlightHex: String = highlightColorHex(HighlightColor.DEFAULT.token)

    /** Baked-in alpha of [HighlightColor.DEFAULT] as a 0..1 fraction. */
    val highlightAlpha: Float = highlightColorAlpha(HighlightColor.DEFAULT.token)
}
