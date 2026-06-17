package com.riffle.core.domain

/**
 * The fixed annotation-highlight palette (ADR 0024): a colour is one of these four tokens, never a
 * freeform hex. [token] is the lowercase string persisted in `AnnotationEntity.color`; [argb] is the
 * full-opacity base hue (the reader theme bakes in per-theme alpha at render time).
 *
 * Self-contained on purpose: the hues match the readaloud palette for visual consistency, but
 * annotations keep their own four-token vocabulary (no PURPLE, yellow default) and their own synced
 * persistence, decoupled from [ReadaloudHighlightColor].
 */
enum class HighlightColor(val token: String, val argb: Int) {
    YELLOW("yellow", 0xFFFBBF24.toInt()),
    GREEN("green", 0xFF34D399.toInt()),
    BLUE("blue", 0xFF38BDF8.toInt()),
    PINK("pink", 0xFFFB7185.toInt());

    companion object {
        val DEFAULT = YELLOW

        /** Map a stored token back to a colour; unknown/null falls back to [DEFAULT] (sync forward-compat). */
        fun fromToken(token: String?): HighlightColor =
            entries.firstOrNull { it.token == token } ?: DEFAULT
    }
}
