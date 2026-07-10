package com.riffle.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract for series ordering. If a new [SeriesCapability] implementation ships and forgets to
 * use [SeriesEntryOrdering], the bug that shows up in the UI is "Book 10 appears before Book 2".
 * These assertions pin the exact behaviour that prevents that — including the tricky corners
 * (decimals, non-numeric strings, missing sequence, ties).
 */
class SeriesEntryOrderingTest {

    private data class Row(val title: String, val sequence: String?)

    private fun sort(vararg rows: Row): List<Row> = rows.toList().sortedWith(
        SeriesEntryOrdering.comparator(sequenceOf = { it.sequence }, titleOf = { it.title }),
    )

    @Test
    fun `numeric sequences sort by value not lexicographically`() {
        // The whole reason SeriesEntryOrdering exists: naive string sort puts "10" before "2".
        val sorted = sort(
            Row("Ten", "10"),
            Row("Two", "2"),
            Row("One", "1"),
        )
        assertEquals(listOf("One", "Two", "Ten"), sorted.map { it.title })
    }

    @Test
    fun `decimal and integer sequences interleave correctly`() {
        val sorted = sort(
            Row("Full 2", "2"),
            Row("Novella", "2.5"),
            Row("Full 3", "3"),
            Row("Full 1", "1"),
        )
        assertEquals(listOf("Full 1", "Full 2", "Novella", "Full 3"), sorted.map { it.title })
    }

    @Test
    fun `non-numeric sequences sort after numeric ones and among themselves alphabetically`() {
        val sorted = sort(
            Row("Numbered 2", "2"),
            Row("Side Story", "side-story"),
            Row("Prequel", "prequel"),
            Row("Numbered 1", "1"),
        )
        assertEquals(
            listOf("Numbered 1", "Numbered 2", "Prequel", "Side Story"),
            sorted.map { it.title },
        )
    }

    @Test
    fun `missing sequences sort last ordered by title`() {
        val sorted = sort(
            Row("Zebra", null),
            Row("Two", "2"),
            Row("Aardvark", null),
            Row("One", "1"),
        )
        assertEquals(listOf("One", "Two", "Aardvark", "Zebra"), sorted.map { it.title })
    }

    @Test
    fun `blank sequence string treated as missing not as zero`() {
        val sorted = sort(
            Row("Zero-a", "0"),
            Row("Blank-titled", "   "),
            Row("One", "1"),
        )
        assertEquals(listOf("Zero-a", "One", "Blank-titled"), sorted.map { it.title })
    }

    // Regression: `"NaN".toDoubleOrNull()` is non-null and NaN sorts as greater-than +∞ under
    // Double.compareTo, which would otherwise land NaN entries in the numeric bucket AFTER every
    // finite numeric row. Non-finite tokens must fall through to the non-numeric bucket instead.
    @Test
    fun `NaN and Infinity in the sequence string fall through to the non-numeric bucket`() {
        val sorted = sort(
            Row("Two", "2"),
            Row("Buggy", "NaN"),
            Row("Also Buggy", "Infinity"),
            Row("One", "1"),
        )
        // "1" and "2" are the two numeric entries — they must precede the two non-finite ones,
        // which then tiebreak alphabetically on title.
        assertEquals(listOf("One", "Two", "Also Buggy", "Buggy"), sorted.map { it.title })
    }

    @Test
    fun `equal sequence breaks tie on title case-insensitively`() {
        val sorted = sort(
            Row("beta", "1"),
            Row("Alpha", "1"),
            Row("gamma", "1"),
        )
        assertEquals(listOf("Alpha", "beta", "gamma"), sorted.map { it.title })
    }
}
