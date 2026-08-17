package com.riffle.core.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LegacyFolderLibraryIdTest {
    @Test
    fun sameFolderProducesStableIdentity() {
        assertEquals(
            legacyFolderLibraryId("source", "content://tree/books"),
            legacyFolderLibraryId("source", "content://tree/books"),
        )
    }

    @Test
    fun tupleBoundariesCannotCollide() {
        assertNotEquals(
            legacyFolderLibraryId("a", "bc"),
            legacyFolderLibraryId("ab", "c"),
        )
    }

    @Test
    fun keepsLocalFolderNamespace() {
        assertTrue(legacyFolderLibraryId("source", "tree").startsWith("local:folder:"))
    }
}
