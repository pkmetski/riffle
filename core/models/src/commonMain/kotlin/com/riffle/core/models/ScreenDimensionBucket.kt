package com.riffle.core.models

/**
 * Rotation-invariant screen-dimension key. Always stored as (narrower, wider) so portrait and
 * landscape on the same device produce the same bucket — a phone at Compact×Medium in portrait
 * and Medium×Compact in landscape both yield "Compact_Medium".
 */
data class ScreenDimensionBucket(
    val narrower: SizeClass,
    val wider: SizeClass,
) {
    enum class SizeClass { Compact, Medium, Expanded }

    fun encode(): String = "${narrower.name}_${wider.name}"

    companion object {
        fun decode(value: String): ScreenDimensionBucket {
            val parts = value.split("_")
            return ScreenDimensionBucket(
                narrower = SizeClass.valueOf(parts[0]),
                wider = SizeClass.valueOf(parts[1]),
            )
        }

        fun of(a: SizeClass, b: SizeClass): ScreenDimensionBucket =
            if (a.ordinal <= b.ordinal) ScreenDimensionBucket(a, b) else ScreenDimensionBucket(b, a)

        // Migration default: typical phone portrait (Compact width × Medium height).
        // encode() of this must equal exactly 'Compact_Medium' — the SQL DEFAULT.
        val PhonePortrait = ScreenDimensionBucket(SizeClass.Compact, SizeClass.Medium)
    }
}
