package com.riffle.core.data.dictionary

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File

fun interface JsonlToSqliteConverter {
    fun convert(jsonlFile: File, dbFile: File)
}

internal class KaikkiJsonlToSqliteConverter : JsonlToSqliteConverter {
    override fun convert(jsonlFile: File, dbFile: File) {
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
                "INSERT OR REPLACE INTO entries(form, pos, glosses) VALUES(?,?,?)"
            )

            var rowCount = 0
            db.beginTransaction()
            try {
                BufferedReader(jsonlFile.reader()).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        try {
                            val obj = JSONObject(line)
                            val form = obj.optString("word").trim()
                            val pos = obj.optString("pos").trim()
                            if (form.isBlank()) {
                                line = reader.readLine()
                                continue
                            }
                            val glosses = extractGlosses(obj)
                            if (glosses.length() == 0) {
                                line = reader.readLine()
                                continue
                            }
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
                        } catch (_: Exception) {
                            // malformed line: skip
                        }
                        line = reader.readLine()
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
