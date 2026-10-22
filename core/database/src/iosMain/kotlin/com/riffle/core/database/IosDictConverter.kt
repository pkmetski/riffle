@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.riffle.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingAtPath

private const val MAX_BUFFER_ENTRIES = 2_000
private const val CHUNK_SIZE: ULong = 1_048_576u // 1 MB per read

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

        val fileHandle = NSFileHandle.fileHandleForReadingAtPath(jsonlPath)
            ?: throw IllegalStateException("Cannot open JSONL at $jsonlPath")
        val totalBytes = fileHandle.seekToEndOfFile().toLong()
        fileHandle.seekToFileOffset(0uL)
        val effectiveTotalBytes = totalBytes.coerceAtLeast(1L)

        val buffer = HashMap<Pair<String, String>, MutableList<String>>(MAX_BUFFER_ENTRIES * 2)
        var bytesProcessed = 0L
        var lineCount = 0
        var partial = ""

        try {
            while (true) {
                val data = fileHandle.readDataOfLength(CHUNK_SIZE)
                if (data.length == 0uL) break
                bytesProcessed += data.length.toLong()

                val chunk = NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
                    ?: break
                val combined = partial + chunk
                val newlineIdx = combined.lastIndexOf('\n')
                if (newlineIdx < 0) {
                    partial = combined
                    continue
                }
                partial = combined.substring(newlineIdx + 1)
                val lines = combined.substring(0, newlineIdx)

                for (line in lines.lineSequence()) {
                    if (line.isBlank()) continue
                    lineCount++
                    if (lineCount % 5_000 == 0) onProgress(bytesProcessed.coerceAtMost(effectiveTotalBytes), effectiveTotalBytes)
                    processLine(line, buffer)
                    if (buffer.size >= MAX_BUFFER_ENTRIES) {
                        flushBuffer(conn, buffer)
                        buffer.clear()
                    }
                }
            }
            if (partial.isNotBlank()) processLine(partial, buffer)
            if (buffer.isNotEmpty()) flushBuffer(conn, buffer)
        } finally {
            fileHandle.closeFile()
        }

        onProgress(effectiveTotalBytes, effectiveTotalBytes)

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

private fun processLine(line: String, buffer: HashMap<Pair<String, String>, MutableList<String>>) {
    try {
        val obj = Json.parseToJsonElement(line).jsonObject
        val form = obj["word"]?.jsonPrimitive?.content?.trim() ?: return
        if (form.isBlank()) return
        val pos = obj["pos"]?.jsonPrimitive?.content?.trim() ?: ""
        val glosses = extractGlosses(obj)
        if (glosses.isNotEmpty()) {
            buffer.getOrPut(form to pos) { mutableListOf() }.addAll(glosses)
        }
    } catch (_: Exception) {}
}

private fun flushBuffer(
    conn: androidx.sqlite.SQLiteConnection,
    buffer: Map<Pair<String, String>, List<String>>,
) {
    conn.execSQL("BEGIN TRANSACTION")
    try {
        val insertStmt = conn.prepare("INSERT OR IGNORE INTO entries(form, pos, glosses) VALUES(?,?,?)")
        val changesStmt = conn.prepare("SELECT changes()")
        // SUBSTR(glosses, 1, LENGTH(glosses)-1) strips the trailing ']' so the new gloss slots in
        // before it, producing valid JSON. The NOT LIKE guard uses the JSON-quoted form so partial
        // matches on substrings (e.g. "bank" inside "river bank") are not suppressed incorrectly.
        val appendStmt = conn.prepare(
            "UPDATE entries SET glosses = SUBSTR(glosses, 1, LENGTH(glosses)-1) || ? " +
                "WHERE form = ? AND pos = ? AND glosses NOT LIKE '%' || ? || '%'",
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
                changesStmt.step()
                val wasInserted = changesStmt.getLong(0) > 0
                changesStmt.reset()

                if (!wasInserted) {
                    // Entry already existed from a previous flush — append any new glosses.
                    for (gloss in glosses) {
                        val fragment = ",${jsonQuote(gloss)}]"
                        val quoted = jsonQuote(gloss)
                        appendStmt.bindText(1, fragment)
                        appendStmt.bindText(2, form)
                        appendStmt.bindText(3, pos)
                        appendStmt.bindText(4, quoted)
                        appendStmt.step()
                        appendStmt.reset()
                    }
                }
            }
        } finally {
            insertStmt.close()
            changesStmt.close()
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
