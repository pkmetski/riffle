package com.riffle.core.database

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.remove
import kotlin.test.Test
import kotlin.test.assertEquals

class IosRiffleDatabaseTest {
    @Test
    fun bundledDriverCreatesDatabaseAndPreservesFlowQueriesOnIos() = runTest {
        val path = "${NSTemporaryDirectory()}riffle-room-kmp-${NSUUID().UUIDString}.db"
        val database = openRiffleDatabase(path)
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
            remove(path)
            remove("$path-shm")
            remove("$path-wal")
        }
    }
}
