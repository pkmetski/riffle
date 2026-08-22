package com.riffle.core.models

data class ScreenDimensionBucket(
    val widthSizeClass: Width,
    val heightSizeClass: Height,
) {
    enum class Width { Compact, Medium, Expanded }
    enum class Height { Compact, Medium, Expanded }

    fun encode(): String = "${widthSizeClass.name}_${heightSizeClass.name}"

    companion object {
        fun decode(value: String): ScreenDimensionBucket {
            val parts = value.split("_")
            return ScreenDimensionBucket(
                widthSizeClass = Width.valueOf(parts[0]),
                heightSizeClass = Height.valueOf(parts[1]),
            )
        }

        // Migration default: existing rows are attributed to a large-screen context.
        // encode() of this must equal exactly 'Expanded_Medium' — the SQL DEFAULT.
        val NonCompact = ScreenDimensionBucket(Width.Expanded, Height.Medium)
    }
}
