@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.riffle.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile

private const val MAX_BUFFER_ENTRIES = 2_000

/**
 * Converts a JSONL file at [jsonlPath] (Kaikki/Wiktextract format) into a SQLite dictionary
 * at [dbPath]. Called from IosPackDownloaderImpl after downloading the JSONL.
 *
 * Separated into core:database because BundledSQLiteDriver is a persistence implementation detail
 * that must not leak into other modules (ADR 0048).
 *
 * @throws IllegalStateException if the JSONL is empty or malformed.
 */
fun createDictSqliteFromJsonl(
    jsonlPath: String,
    dbPath: String,
    onProgress: (processedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
) {
    NSFileManager.defaultManager.removeItemAtPath(dbPath, error = null)
    val conn = BundledSQLiteDriver().open(dbPath)
    try {
        conn.execSQL(
            "CREATE TABLE entries (form TEXT NOT NULL, pos TEXT NOT NULL DEFAULT '', " +
                "glosses TEXT NOT NULL, PRIMARY KEY (form, pos))",
        )
        conn.execSQL("CREATE INDEX entries_form ON entries(form COLLATE NOCASE)")

        val nsData = NSData.dataWithContentsOfFile(jsonlPath)
            ?: throw IllegalStateException("Cannot read JSONL at $jsonlPath")
        val totalBytes = nsData.length.toLong().coerceAtLeast(1L)
        val text = NSString.create(data = nsData, encoding = NSUTF8StringEncoding)?.toString()
            ?: throw IllegalStateException("JSONL file is not valid UTF-8")

        val buffer = HashMap<Pair<String, String>, MutableList<String>>(MAX_BUFFER_ENTRIES * 2)
        var bytesProcessed = 0L
        var lineCount = 0

        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            bytesProcessed += line.length + 1L
            lineCount++
            if (lineCount % 5_000 == 0) onProgress(bytesProcessed.coerceAtMost(totalBytes), totalBytes)
            try {
                val obj = Json.parseToJsonElement(line).jsonObject
                val form = obj["word"]?.jsonPrimitive?.content?.trim() ?: continue
                if (form.isBlank()) continue
                val pos = obj["pos"]?.jsonPrimitive?.content?.trim() ?: ""
                val glosses = extractGlosses(obj)
                if (glosses.isNotEmpty()) {
                    buffer.getOrPut(form to pos) { mutableListOf() }.addAll(glosses)
                }
            } catch (_: Exception) {}

            if (buffer.size >= MAX_BUFFER_ENTRIES) {
                flushBuffer(conn, buffer)
                buffer.clear()
            }
        }
        if (buffer.isNotEmpty()) flushBuffer(conn, buffer)
        onProgress(totalBytes, totalBytes)

        val stmt = conn.prepare("SELECT COUNT(*) FROM entries")
        val count = if (stmt.step()) stmt.getLong(0) else 0L
        stmt.close()
        if (count == 0L) throw IllegalStateException("No valid entries in JSONL — may be malformed or an error page")
    } finally {
        conn.close()
    }
}

/** Opens the dict SQLite at [dbPath] and counts its entries. Used by tests to verify conversion. */
fun countDictSqliteEntries(dbPath: String): Long {
    val conn = BundledSQLiteDriver().open(dbPath)
    return try {
        val stmt = conn.prepare("SELECT COUNT(*) FROM entries")
        val count = if (stmt.step()) stmt.getLong(0) else 0L
        stmt.close()
        count
    } finally {
        conn.close()
    }
}

private fun flushBuffer(
    conn: androidx.sqlite.SQLiteConnection,
    buffer: Map<Pair<String, String>, List<String>>,
) {
    conn.execSQL("BEGIN TRANSACTION")
    try {
        val insertStmt = conn.prepare("INSERT OR IGNORE INTO entries(form, pos, glosses) VALUES(?,?,?)")
        val appendStmt = conn.prepare(
            "UPDATE entries SET glosses = glosses || ? WHERE form = ? AND pos = ? AND glosses NOT LIKE '%' || ? || '%'",
        )
        try {
            for ((key, glosses) in buffer) {
                val (form, pos) = key
                val glossesJson = glosses.joinToString(",", "[", "]") { jsonQuote(it) }
                insertStmt.bindText(1, form)
                insertStmt.bindText(2, pos)
                insertStmt.bindText(3, glossesJson)
                insertStmt.step()
                insertStmt.reset()
                for (gloss in glosses) {
                    val fragment = ",${jsonQuote(gloss)}]"
                    appendStmt.bindText(1, fragment)
                    appendStmt.bindText(2, form)
                    appendStmt.bindText(3, pos)
                    appendStmt.bindText(4, gloss)
                    appendStmt.step()
                    appendStmt.reset()
                }
            }
        } finally {
            insertStmt.close()
            appendStmt.close()
        }
        conn.execSQL("COMMIT")
    } catch (e: Exception) {
        runCatching { conn.execSQL("ROLLBACK") }
        throw e
    }
}

private fun extractGlosses(obj: kotlinx.serialization.json.JsonObject): List<String> {
    val senses = obj["senses"]?.jsonArray ?: return emptyList()
    return buildList {
        for (sense in senses) {
            val first = sense.jsonObject["glosses"]?.jsonArray
                ?.firstOrNull()?.jsonPrimitive?.content?.trim()
            if (!first.isNullOrBlank()) add(first)
        }
    }
}

private fun jsonQuote(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u${c.code.toString(16).padStart(4, '0')}") else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
