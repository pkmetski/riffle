package com.riffle.feature.downloads

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Renders a byte count as a compact human-readable size — "312 MB", "1.2 GB", "4.8 MB".
 *
 * Reproduces Android's `String.format(Locale.US, "%.1f %s" / "%.0f %s", …)` exactly, by hand,
 * because `String.format` is JVM-only. Sizes of 100 units or more drop the decimal, so the
 * string never grows past five characters plus the unit.
 *
 * iOS used to carry its own integer-division version that stopped at GB: 5 000 000 B rendered
 * "4.8 MB" on Android and "4 MB" on iOS, and a terabyte-sized cache rendered as a four-digit
 * gigabyte count. Both platforms call this now.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val unit = units[unitIndex]
    return if (value >= 100) "${value.roundToLong()} $unit" else "${value.toOneDecimal()} $unit"
}

/** `%.1f` without `String.format`: round half-up to a tenth, then print the two halves. */
private fun Double.toOneDecimal(): String {
    val tenths = (this * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

/**
 * Presentation order for the media-type badge on a downloads row. Android sorted by this;
 * iOS joined the `Set` in iteration order, so the same EPUB+audiobook item could read
 * "Audiobook + EPUB" on one platform and "EPUB + Audiobook" on the other.
 */
val LocalMediaType.displayOrder: Int
    get() = when (this) {
        LocalMediaType.Epub -> 0
        LocalMediaType.Pdf -> 1
        LocalMediaType.Comic -> 2
        LocalMediaType.Audiobook -> 3
        LocalMediaType.Readaloud -> 4
    }

/** Untranslated badge label. Android renders the localized variant inside a Composable scope. */
fun LocalMediaType.label(): String = when (this) {
    LocalMediaType.Epub -> "EPUB"
    LocalMediaType.Pdf -> "PDF"
    LocalMediaType.Comic -> "Comic"
    LocalMediaType.Audiobook -> "Audiobook"
    LocalMediaType.Readaloud -> "Readaloud"
}

/** "EPUB + Audiobook" — every type the item has locally, in [displayOrder]. */
fun Set<LocalMediaType>.displayLabel(): String =
    sortedBy { it.displayOrder }.joinToString(" + ") { it.label() }
