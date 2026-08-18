package com.riffle.core.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageCatalogTest {

    @Test
    fun `all entries have non-blank fields`() {
        for (entry in LanguageCatalog.all) {
            assertTrue("languageTag blank for $entry", entry.languageTag.isNotBlank())
            assertTrue("displayName blank for $entry", entry.displayName.isNotBlank())
            assertTrue("jsonlUrl blank for $entry", entry.jsonlUrl.isNotBlank())
            assertTrue("approximateSizeBytes <= 0 for $entry", entry.approximateSizeBytes > 0)
        }
    }

    @Test
    fun `jsonlUrl contains displayName`() {
        for (entry in LanguageCatalog.all) {
            assertTrue(
                "jsonlUrl '${entry.jsonlUrl}' does not reference displayName '${entry.displayName}'",
                entry.jsonlUrl.contains(entry.displayName, ignoreCase = true)
            )
        }
    }

    @Test
    fun `entryFor returns correct entry`() {
        val fr = LanguageCatalog.entryFor("fr")
        assertNotNull(fr)
        assertEquals("fr", fr!!.languageTag)
        assertEquals("French", fr.displayName)
    }

    @Test
    fun `entryFor returns null for unknown tag`() {
        assertEquals(null, LanguageCatalog.entryFor("xx"))
    }

    @Test
    fun `catalog has at least 10 entries`() {
        assertTrue("Expected at least 10 catalog entries", LanguageCatalog.all.size >= 10)
    }
}
