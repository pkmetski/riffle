package com.riffle.core.dictionary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageCatalogTest {

    @Test
    fun `all entries have non-blank fields`() {
        for (entry in LanguageCatalog.all) {
            assertTrue(entry.languageTag.isNotBlank(), "languageTag blank for $entry")
            assertTrue(entry.displayName.isNotBlank(), "displayName blank for $entry")
            assertTrue(entry.jsonlUrl.isNotBlank(), "jsonlUrl blank for $entry")
            assertTrue(entry.approximateSizeBytes > 0, "approximateSizeBytes <= 0 for $entry")
        }
    }

    @Test
    fun `jsonlUrl contains displayName`() {
        for (entry in LanguageCatalog.all) {
            assertTrue(
                entry.jsonlUrl.contains(entry.displayName, ignoreCase = true),
                "jsonlUrl '${entry.jsonlUrl}' does not reference displayName '${entry.displayName}'",
            )
        }
    }

    @Test
    fun `entryFor returns correct entry`() {
        val fr = LanguageCatalog.entryFor("fr")
        assertNotNull(fr)
        assertEquals("fr", fr.languageTag)
        assertEquals("French", fr.displayName)
    }

    @Test
    fun `entryFor returns null for unknown tag`() {
        assertEquals(null, LanguageCatalog.entryFor("xx"))
    }

    @Test
    fun `catalog has at least 10 entries`() {
        assertTrue(LanguageCatalog.all.size >= 10, "Expected at least 10 catalog entries")
    }

    @Test
    fun `Bulgarian is present and Arabic and Turkish are absent`() {
        assertNotNull(LanguageCatalog.entryFor("bg"), "Bulgarian (bg) must be in catalog")
        assertNull(LanguageCatalog.entryFor("ar"), "Arabic (ar) must not be in catalog")
        assertNull(LanguageCatalog.entryFor("tr"), "Turkish (tr) must not be in catalog")
    }
}
