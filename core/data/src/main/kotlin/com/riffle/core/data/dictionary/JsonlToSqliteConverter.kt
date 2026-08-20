package com.riffle.core.data.dictionary

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File

interface JsonlToSqliteConverter {
    fun convert(jsonlFile: File, dbFile: File, onProgress: (processed: Long, total: Long) -> Unit = { _, _ -> })
}

internal class KaikkiJsonlToSqliteConverter : JsonlToSqliteConverter {

    override fun convert(jsonlFile: File, dbFile: File, onProgress: (Long, Long) -> Unit) {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL(
                """CREATE TABLE entries (
                    form    TEXT NOT NULL,
                    pos     TEXT NOT NULL DEFAULT '',
                    glosses TEXT NOT NULL,
                    PRIMARY KEY (form, pos)
                )"""
            )
            db.execSQL("CREATE INDEX entries_form ON entries(form COLLATE NOCASE)")

            val fileSize = jsonlFile.length().coerceAtLeast(1L)
            var bytesApprox = 0L
            var lineNum = 0

            // Buffer accumulates (form, pos) → glosses for up to MAX_BUFFER_ENTRIES unique keys,
            // then flushes to SQLite. Flushing uses INSERT OR IGNORE + append-via-UPDATE so
            // cross-flush merges of the same (form, pos) key are preserved correctly.
            val buffer = HashMap<Pair<String, String>, JSONArray>(MAX_BUFFER_ENTRIES * 2)

            BufferedReader(jsonlFile.reader()).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    bytesApprox += line.length + 1L
                    lineNum++
                    if (lineNum % 5_000 == 0) {
                        onProgress(bytesApprox.coerceAtMost(fileSize), fileSize)
                    }
                    try {
                        val obj = JSONObject(line)
                        val form = obj.optString("word").trim()
                        val pos = obj.optString("pos").trim()
                        if (form.isNotBlank()) {
                            val glosses = extractGlosses(obj)
                            if (glosses.length() > 0) {
                                val key = Pair(form, pos)
                                val existing = buffer.getOrPut(key) { JSONArray() }
                                for (i in 0 until glosses.length()) {
                                    existing.put(glosses.getString(i))
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // malformed line: skip
                    }

                    if (buffer.size >= MAX_BUFFER_ENTRIES) {
                        flushBuffer(db, buffer)
                    }

                    line = reader.readLine()
                }
            }

            if (buffer.isNotEmpty()) {
                flushBuffer(db, buffer)
            }

            onProgress(fileSize, fileSize)

            val count = db.rawQuery("SELECT COUNT(*) FROM entries", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
            if (count == 0L) {
                throw IllegalStateException("No valid entries found in JSONL — file may be malformed or an HTML error page")
            }
        } finally {
            db.close()
        }
    }

    private fun flushBuffer(db: SQLiteDatabase, buffer: HashMap<Pair<String, String>, JSONArray>) {
        // INSERT OR IGNORE skips existing rows; UPDATE then appends glosses to any pre-existing row
        // so cross-flush merges of the same (form, pos) pair are correct.
        val insert = db.compileStatement(
            "INSERT OR IGNORE INTO entries(form, pos, glosses) VALUES(?,?,?)"
        )
        val appendGlosses = db.compileStatement(
            "UPDATE entries SET glosses = glosses || ? WHERE form = ? AND pos = ? AND glosses NOT LIKE '%' || ? || '%'"
        )

        var rowCount = 0
        db.beginTransaction()
        try {
            for ((key, glosses) in buffer) {
                val (form, pos) = key
                val glossesJson = glosses.toString()

                insert.bindString(1, form)
                insert.bindString(2, pos)
                insert.bindString(3, glossesJson)
                val inserted = insert.executeInsert()

                if (inserted == -1L) {
                    // Row existed from a prior flush — append new glosses entries individually
                    for (i in 0 until glosses.length()) {
                        val g = glosses.getString(i)
                        // Append as a JSON fragment: strip trailing ] from stored, prepend comma+quoted entry
                        val fragment = ",${JSONObject.quote(g)}]"
                        appendGlosses.bindString(1, fragment)
                        appendGlosses.bindString(2, form)
                        appendGlosses.bindString(3, pos)
                        appendGlosses.bindString(4, g)
                        appendGlosses.execute()
                    }
                }

                rowCount++
                if (rowCount % 10_000 == 0) {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    db.beginTransaction()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        buffer.clear()
    }

    private fun extractGlosses(obj: JSONObject): JSONArray {
        val result = JSONArray()
        val senses = obj.optJSONArray("senses") ?: return result
        for (i in 0 until senses.length()) {
            val sense = senses.optJSONObject(i) ?: continue
            val glosses = sense.optJSONArray("glosses") ?: continue
            if (glosses.length() > 0) {
                result.put(glosses.optString(0))
            }
        }
        return result
    }

    companion object {
        // Flush to SQLite after this many unique (form, pos) keys to cap heap usage.
        // At ~200 bytes average per entry this keeps the buffer under ~10 MB.
        private const val MAX_BUFFER_ENTRIES = 50_000
    }
}
