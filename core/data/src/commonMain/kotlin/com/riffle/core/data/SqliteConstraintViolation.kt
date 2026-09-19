package com.riffle.core.data

/**
 * Multiplatform replacement for catching `android.database.sqlite.SQLiteConstraintException` /
 * `SQLiteException` by type, which [ReadaloudMatchingService] used while it was Android-only.
 *
 * The driver types differ per platform (Room's BundledSQLiteDriver on Android, SQLDelight's
 * NativeSqliteDriver on iOS) and neither is visible from commonMain, so the classification is made
 * on the exception's own reported identity instead. Semantics are preserved exactly:
 *
 * - a typed constraint exception (class name contains "Constraint") is a violation;
 * - a SQLite-family exception whose message mentions "constraint" is a violation;
 * - a SQLite-family exception with no message at all is treated as a violation, keeping the
 *   broad-catch safe default the Android code documented;
 * - anything else (SQLITE_FULL, SQLITE_CORRUPT, non-SQLite failures) is **not** a violation and
 *   must be re-thrown rather than silently swallowed.
 */
internal fun Throwable.isSqliteConstraintViolation(): Boolean {
    val className = this::class.simpleName.orEmpty()
    if (className.contains("Constraint", ignoreCase = true)) return true

    val looksLikeSqlite = className.contains("SQL", ignoreCase = true)
    if (!looksLikeSqlite) return false

    val message = message ?: return true
    return message.contains("constraint", ignoreCase = true)
}
