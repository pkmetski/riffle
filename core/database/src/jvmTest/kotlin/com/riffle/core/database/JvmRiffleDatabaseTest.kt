package com.riffle.core.database

import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmRiffleDatabaseTest {
    @Test
    fun bundledDriverCreatesDatabaseAndPreservesFlowQueries() = runTest {
        val directory = Files.createTempDirectory("riffle-room-kmp-test").toFile()
        val database = openRiffleDatabase(directory.resolve("riffle.db").absolutePath)
        val source = SourceEntity(
            id = "source-1",
            url = "https://example.test",
            isActive = true,
            insecureConnectionAllowed = false,
            username = "reader",
        )

        try {
            database.sourceDao().upsert(source)

            assertEquals(source, database.sourceDao().getById(source.id))
            assertEquals(listOf(source), database.sourceDao().observeAll().first())
        } finally {
            database.close()
            directory.deleteRecursively()
        }
    }
}
