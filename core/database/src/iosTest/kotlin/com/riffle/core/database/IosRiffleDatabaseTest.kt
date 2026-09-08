package com.riffle.core.database

import co.touchlab.sqliter.DatabaseFileContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals

class IosRiffleDatabaseTest {
    @Test
    fun nativeSqliteDriverCreatesDatabaseAndPreservesFlowQueriesOnIos() = runTest {
        // A bare filename, exactly as production passes it (`openRiffleDatabase("riffle.db")` in
        // IosDatabaseKoinModule). SQLiter treats the argument as a NAME and resolves it against
        // its own base path; handing it a full path throws
        // "File … contains a path separator" out of DatabaseConfiguration's checkFilename.
        val name = "riffle-sqldelight-${NSUUID().UUIDString}.db"
        val database = openRiffleDatabase(name)
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
            DatabaseFileContext.deleteDatabase(name)
        }
    }
}
