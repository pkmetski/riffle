package com.riffle.core.database

import androidx.room.Room
import androidx.sqlite.driver.NativeSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IosRiffleDatabaseTest {
    @Test
    fun nativeSqliteDriverCreatesDatabaseAndPreservesFlowQueriesOnIos() = runTest {
        val db = Room.inMemoryDatabaseBuilder<RiffleDatabase>()
            .setDriver(NativeSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val database = DefaultRiffleDatabaseAccess(db)
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
        }
    }
}
