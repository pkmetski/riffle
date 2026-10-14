package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BookmarkNavigationAnchorTest {

    private fun locatorJson(
        href: String = "chapter1.xhtml",
        progression: Double = 0.3,
        fragments: List<String> = emptyList(),
    ): String {
        val fragsStr = if (fragments.isNotEmpty()) ""","fragments":[${fragments.joinToString(",") { "\"$it\"" }}]""" else ""
        return """{"href":"$href","type":"application/xhtml+xml","locations":{"progression":$progression$fragsStr}}"""
    }

    private fun extractProgression(json: String): Double? {
        val locIdx = json.indexOf("\"locations\"")
        if (locIdx < 0) return null
        val progIdx = json.indexOf("\"progression\"", locIdx)
        if (progIdx < 0) return null
        val colonIdx = json.indexOf(':', progIdx)
        val end = json.indexOfFirst(colonIdx + 1) { it == ',' || it == '}' }
        return json.substring(colonIdx + 1, end).trim().toDoubleOrNull()
    }

    private fun extractFragments(json: String): List<String>? {
        val locIdx = json.indexOf("\"locations\"")
        if (locIdx < 0) return null
        val fragIdx = json.indexOf("\"fragments\"", locIdx)
        if (fragIdx < 0) return null
        val arrStart = json.indexOf('[', fragIdx)
        val arrEnd = json.indexOf(']', arrStart)
        if (arrStart < 0 || arrEnd < 0) return null
        val inner = json.substring(arrStart + 1, arrEnd).trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
    }

    private fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return length
    }

    @Test
    fun newStyleBookmarkInjectsFragmentAnchorIntoFragments() {
        val input = locatorJson(progression = 0.43)
        val result = input.withBookmarkNavigationAnchor(fragmentAnchor = "para-ch03", storedProgression = 0.43)
        assertEquals(listOf("para-ch03"), extractFragments(result))
    }

    @Test
    fun newStyleBookmarkOverridesProgressionWithStoredValue() {
        val input = locatorJson(progression = 0.71)
        val result = input.withBookmarkNavigationAnchor(fragmentAnchor = "para-intro", storedProgression = 0.0)
        val progression = extractProgression(result)
        assertNotNull(progression)
        assertEquals(0.0, progression, 0.000001)
    }

    @Test
    fun newStyleBookmarkReplacesExistingFragments() {
        val input = locatorJson(progression = 0.5, fragments = listOf("section-ch01"))
        val result = input.withBookmarkNavigationAnchor(fragmentAnchor = "para-42", storedProgression = 0.5)
        assertEquals(listOf("para-42"), extractFragments(result))
    }

    @Test
    fun legacyBookmarkClearsFragments() {
        val input = locatorJson(progression = 0.43, fragments = listOf("section-ch01"))
        val result = input.withBookmarkNavigationAnchor(fragmentAnchor = null, storedProgression = 0.43)
        val frags = extractFragments(result)
        assertTrue(frags?.isEmpty() == true)
    }

    @Test
    fun legacyBookmarkWithoutFragmentsIsUnchanged() {
        val input = locatorJson(progression = 0.43)
        val result = input.withBookmarkNavigationAnchor(fragmentAnchor = null, storedProgression = 0.43)
        assertEquals(null, extractFragments(result))
    }
}
