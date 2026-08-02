package com.riffle.core.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/**
 * Compatibility helpers for the historical migration SQL while the database uses Room's
 * multiplatform [SQLiteConnection] API.
 *
 * They deliberately mirror the tiny subset of `SupportSQLiteDatabase`/Cursor used by
 * [RiffleDatabase]: parameterized statements and forward-only reads. Keeping the adapter here
 * lets every migration retain its reviewed SQL byte-for-byte.
 */
internal fun SQLiteConnection.execSQL(sql: String, bindArgs: Array<out Any?>) {
    prepare(sql).use { statement ->
        bindArgs.forEachIndexed { index, value ->
            statement.bind(index + 1, value)
        }
        statement.step()
    }
}

internal fun SQLiteConnection.query(sql: String): MigrationCursor =
    MigrationCursor(prepare(sql))

internal class MigrationCursor(
    private val statement: SQLiteStatement,
) : AutoCloseable {
    fun moveToFirst(): Boolean = statement.step()

    fun moveToNext(): Boolean = statement.step()

    fun getString(index: Int): String = statement.getText(index)

    fun getLong(index: Int): Long = statement.getLong(index)

    override fun close() {
        statement.close()
    }
}

private fun SQLiteStatement.bind(index: Int, value: Any?) {
    when (value) {
        null -> bindNull(index)
        is ByteArray -> bindBlob(index, value)
        is Boolean -> bindBoolean(index, value)
        is Byte -> bindLong(index, value.toLong())
        is Short -> bindLong(index, value.toLong())
        is Int -> bindInt(index, value)
        is Long -> bindLong(index, value)
        is Float -> bindFloat(index, value)
        is Double -> bindDouble(index, value)
        is String -> bindText(index, value)
        else -> error("Unsupported SQLite migration bind value: ${value::class}")
    }
}
