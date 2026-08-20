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
        // Phase 1: Parse JSONL and accumulate glosses per (form, pos).
        // kaikki.org emits one line per etymology, so the same word+pos may appear multiple times.
        // Accumulating here merges all etymologies' senses rather than silently overwriting them.
        val accumulated = HashMap<Pair<String, String>, JSONArray>()
        val fileSize = jsonlFile.length().coerceAtLeast(1L)
        var bytesApprox = 0L
        var lineNum = 0
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
                            val existing = accumulated.getOrPut(key) { JSONArray() }
                            for (i in 0 until glosses.length()) {
                                existing.put(glosses.getString(i))
                            }
                        }
                    }
                } catch (_: Exception) {
                    // malformed line: skip
                }
                line = reader.readLine()
            }
        }
        onProgress(fileSize, fileSize)

        if (accumulated.isEmpty()) {
            throw IllegalStateException("No valid entries found in JSONL — file may be malformed or an HTML error page")
        }

        // Phase 2: Write to SQLite. Batch-commit logic is outside the per-line catch so
        // disk-full and other DB exceptions propagate correctly to PackDownloader's error handler.
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

            val insert = db.compileStatement(
                "INSERT INTO entries(form, pos, glosses) VALUES(?,?,?)"
            )

            var rowCount = 0
            db.beginTransaction()
            try {
                for ((key, glosses) in accumulated) {
                    val (form, pos) = key
                    insert.bindString(1, form)
                    insert.bindString(2, pos)
                    insert.bindString(3, glosses.toString())
                    insert.executeInsert()
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
        } finally {
            db.close()
        }
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
}
