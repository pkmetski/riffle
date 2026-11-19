package com.riffle.core.data.dictionary

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File

internal class JvmJsonlToSqliteConverter : JsonlToSqliteConverter {

    override suspend fun convert(
        jsonlPath: String,
        dbPath: String,
        onProgress: (processed: Long, total: Long) -> Unit,
    ) = KaikkiConverterCore.convert(File(jsonlPath), File(dbPath), onProgress)
}

private object KaikkiConverterCore {
    private const val MAX_BUFFER_ENTRIES = 50_000

    fun convert(jsonlFile: File, dbFile: File, onProgress: (Long, Long) -> Unit) {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            db.execSQL(
                """CREATE TABLE entries (
                    form    TEXT NOT NULL,
                    pos     TEXT NOT NULL DEFAULT '',
                    glosses TEXT NOT NULL,
                    PRIMARY KEY (form, pos)
                )""",
            )
            db.execSQL("CREATE INDEX entries_form ON entries(form COLLATE NOCASE)")

            val fileSize = jsonlFile.length().coerceAtLeast(1L)
            var bytesApprox = 0L
            var lineNum = 0
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
                                for (i in 0 until glosses.length()) existing.put(glosses.getString(i))
                            }
                        }
                    } catch (_: Exception) {}

                    if (buffer.size >= MAX_BUFFER_ENTRIES) flushBuffer(db, buffer)

                    line = reader.readLine()
                }
            }

            if (buffer.isNotEmpty()) flushBuffer(db, buffer)
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
        val insert = db.compileStatement("INSERT OR IGNORE INTO entries(form, pos, glosses) VALUES(?,?,?)")
        val appendGlosses = db.compileStatement(
            "UPDATE entries SET glosses = glosses || ? WHERE form = ? AND pos = ? AND glosses NOT LIKE '%' || ? || '%'",
        )
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
                    for (i in 0 until glosses.length()) {
                        val g = glosses.getString(i)
                        val fragment = ",${JSONObject.quote(g)}]"
                        appendGlosses.bindString(1, fragment)
                        appendGlosses.bindString(2, form)
                        appendGlosses.bindString(3, pos)
                        appendGlosses.bindString(4, g)
                        appendGlosses.execute()
                    }
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
            if (glosses.length() > 0) result.put(glosses.optString(0))
        }
        return result
    }
}
