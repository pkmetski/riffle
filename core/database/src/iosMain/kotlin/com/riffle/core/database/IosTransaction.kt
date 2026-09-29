package com.riffle.core.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Wraps [block] in an explicit SQLite IMMEDIATE transaction.
 *
 * Without this, every individual [SqlDriver.execute] call on iOS is an implicit auto-commit
 * transaction that triggers its own fsync in WAL mode (~5–15 ms on NAND flash). For a batch
 * write of N rows that cost O(N * fsync) on iOS vs O(1 * fsync) on Android (where Room's
 * `@Transaction` does this automatically). A 500-book library refresh was taking 20+ seconds
 * on iOS for exactly this reason.
 *
 * IMMEDIATE is used rather than DEFERRED so the write lock is acquired upfront, preventing
 * other writers from blocking mid-batch.
 */
internal suspend fun SqlDriver.withTransaction(block: suspend () -> Unit) {
    execute(null, "BEGIN IMMEDIATE", 0)
    try {
        block()
        execute(null, "COMMIT", 0)
    } catch (e: Throwable) {
        runCatching { execute(null, "ROLLBACK", 0) }
        throw e
    }
}
