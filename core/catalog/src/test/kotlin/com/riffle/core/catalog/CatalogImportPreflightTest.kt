package com.riffle.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogImportPreflightTest {
    @Test
    fun `missing item uploads without asking to overwrite`() {
        assertEquals(
            CatalogImportDecision.UploadNewItem,
            catalogImportDecision(itemExists = false, destinationHasAnnotations = false, replacementDiffers = true),
        )
    }

    @Test
    fun `different replacement is blocked when annotations exist`() {
        val decision = catalogImportDecision(
            itemExists = true,
            destinationHasAnnotations = true,
            replacementDiffers = true,
        )

        assertTrue(decision is CatalogImportDecision.Blocked)
    }

    @Test
    fun `existing item can be confirmed when annotations remain valid`() {
        assertEquals(
            CatalogImportDecision.ConfirmOverwrite,
            catalogImportDecision(itemExists = true, destinationHasAnnotations = false, replacementDiffers = true),
        )
    }
}
