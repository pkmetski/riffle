package com.riffle.core.domain

/**
 * The four typographic styles that can layer onto a text range via a `TYPE_EMPHASIS` annotation
 * (ADR 0046). A given emphasis row carries a non-empty [Set] of these; the union of styles from
 * every overlapping row is applied at render time.
 *
 * [token] is the wire form persisted in `AnnotationEntity.emphasisStyles` (comma-separated) and
 * emitted in the W3C `riffle:styles` extension. Kept lowercase and stable so the schema is
 * additive across future style additions.
 */
enum class EmphasisStyle(val token: String) {
    BOLD("bold"),
    ITALIC("italic"),
    UNDERLINE("underline"),
    STRIKE("strike"),
    ;

    companion object {
        /** Reverse lookup — returns `null` for unknown/malformed tokens so a forward-compatible
         *  peer's extra token doesn't crash a stale device. */
        fun fromToken(token: String): EmphasisStyle? =
            entries.firstOrNull { it.token == token }

        /** Encode a set of styles to the stable `TYPE_EMPHASIS` wire form: comma-separated tokens
         *  in enum-declaration order (so identical sets round-trip to identical strings for
         *  dedup/merge equality). Returns null on an empty set — the entity column is nullable to
         *  keep the "no styles" state one representation, not two ("" vs null). */
        fun encode(styles: Set<EmphasisStyle>): String? =
            styles.takeIf { it.isNotEmpty() }
                ?.let { s -> entries.filter { it in s }.joinToString(",") { it.token } }

        /** Decode the comma-separated wire form back to a set, dropping unknown tokens. Returns
         *  null for null / empty input. */
        fun decode(wire: String?): Set<EmphasisStyle>? {
            if (wire.isNullOrBlank()) return null
            val parsed = wire.split(',')
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { fromToken(it) }
                .toSet()
            return parsed.takeIf { it.isNotEmpty() }
        }
    }
}
