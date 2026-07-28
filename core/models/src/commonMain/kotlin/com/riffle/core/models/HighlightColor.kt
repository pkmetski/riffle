package com.riffle.core.models

/**
 * The single-source palette for every reader highlight — user annotations, readaloud "now speaking"
 * markers, and the swatches in both Settings pickers. A colour is one of these four tokens, never a
 * freeform hex. [token] is the lowercase string persisted in `AnnotationEntity.color`; [argb] is the
 * FINAL rendered ARGB — colour AND alpha baked in. Used verbatim by every consumer (settings swatch,
 * annotation popup swatch, reader decoration) so the picker preview and the on-page highlight are
 * literally the same pixel value. To change hue or opacity, edit this file only.
 *
 * Annotations persist [token]; readaloud persists the enum [name] in `ReadaloudPreferences`.
 */
enum class HighlightColor(val token: String, val argb: Int) {
    // Alpha 0x80 (~50%) is baked into every entry — legible body text through the fill on both
    // light and dark themes, saturated enough to read as a deliberate mark. Change the alpha
    // nibble on every row together; there is no per-theme or per-feature override.
    YELLOW("yellow", 0x80FBBF24.toInt()),
    GREEN("green", 0x8034D399.toInt()),
    BLUE("blue", 0x8038BDF8.toInt()),
    RED("red", 0x80EF4444.toInt());

    companion object {
        val DEFAULT = YELLOW

        /** Map a stored token back to a colour; unknown/null falls back to [DEFAULT] (sync forward-compat). */
        fun fromToken(token: String?): HighlightColor =
            entries.firstOrNull { it.token == token } ?: DEFAULT
    }
}
