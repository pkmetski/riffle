package com.riffle.core.catalog

/**
 * Canonical ordering for entries within a series. Every [SeriesCapability] implementation MUST
 * sort its per-series entries with this comparator — do not roll your own or trust upstream
 * order. Rolling your own is how "Book 10" ends up before "Book 2" in the Series tab.
 *
 * Ordering rules, in priority order:
 *
 * 1. **Numeric sequence first.** Entries whose [sequenceOf] parses as a Double (`"1"`, `"2.5"`,
 *    `"10"`) sort by that value ascending — so 1, 2, 2.5, 10, never lexicographic.
 * 2. **Non-numeric sequences next**, ordered alphabetically among themselves. Publishers use
 *    strings like `"prequel"` or `"0.5-novella"` for extras; these deserve a stable spot AFTER
 *    the numbered entries rather than being buried by numeric sort.
 * 3. **Missing sequence last**, ordered by title. An entry with no sequence at all is a data
 *    hole — surface it, but at the end.
 * 4. Ties within any bucket break on title, case-insensitive.
 *
 * The comparator is stable, so entries equal on every key preserve input order.
 */
object SeriesEntryOrdering {
    fun <T> comparator(sequenceOf: (T) -> String?, titleOf: (T) -> String): Comparator<T> =
        compareBy(
            { rankOf(sequenceOf(it)) },
            { sequenceOf(it)?.trim()?.toDoubleOrNull() ?: 0.0 },
            { sequenceOf(it)?.trim()?.lowercase() ?: "" },
            { titleOf(it).lowercase() },
        )

    private fun rankOf(sequence: String?): Int {
        val trimmed = sequence?.trim()
        return when {
            trimmed.isNullOrEmpty() -> 2
            trimmed.toDoubleOrNull() != null -> 0
            else -> 1
        }
    }
}
