package com.riffle.core.database

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseFileContext

fun openRiffleDatabase(path: String): RiffleDatabaseAccess {
    val driver = NativeSqliteDriver(IosRiffleDatabaseSchema, path)
    return IosRiffleDatabaseAccess(driver)
}

/**
 * Removes the SQLite file (and its -wal/-shm companions) behind a database opened with
 * [openRiffleDatabase]. Test fixtures in modules that cannot see SQLiter directly use this to
 * clean up per-test databases.
 */
fun deleteRiffleDatabase(name: String) {
    DatabaseFileContext.deleteDatabase(name)
}
