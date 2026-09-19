package com.riffle.app.feature.library

import com.riffle.core.catalog.CatalogImportPhase
import com.riffle.feature.library.BookImportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookImportManagerTest {

    @Test
    fun `completed import produces a success message`() {
        assertEquals(
            com.riffle.app.R.string.ui_upload_completed,
            bookImportSnackbarMessage(BookImportState.Completed),
        )
    }

    @Test
    fun `non-completed import does not produce a success message`() {
        assertEquals(
            null,
            bookImportSnackbarMessage(BookImportState.InProgress(CatalogImportPhase.Uploading)),
        )
    }
}
