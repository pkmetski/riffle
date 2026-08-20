package com.riffle.core.data.dictionary

import android.database.sqlite.SQLiteDatabase
import com.riffle.core.dictionary.DictionaryEntry
import com.riffle.core.dictionary.PackEntryReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject

class DictionaryPackSqliteStore @Inject constructor(
    private val filesDir: File,
) {
    fun readerForLanguage(languageTag: String): PackEntryReader? {
        val file = packFileFor(languageTag)
        if (!file.exists()) return null
        return SqlitePackEntryReader(file)
    }

    fun deletePackFile(languageTag: String) {
        packFileFor(languageTag).delete()
    }

    fun packFileFor(languageTag: String): File =
        File(filesDir, "dicts/$languageTag.db")

    private class SqlitePackEntryReader(private val file: File) : PackEntryReader {
        override fun query(form: String): List<DictionaryEntry> =
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery(
                    "SELECT form, pos, glosses FROM entries WHERE form = ? COLLATE NOCASE",
                    arrayOf(form),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val glossesRaw = cursor.getString(2)
                            val glosses = parseGlossesJson(glossesRaw)
                            add(
                                DictionaryEntry(
                                    form = cursor.getString(0),
                                    partOfSpeech = cursor.getString(1),
                                    glosses = glosses,
                                )
                            )
                        }
                    }
                }
            }

        private fun parseGlossesJson(raw: String): List<String> =
            try {
                Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
            } catch (_: Exception) {
                emptyList()
            }
    }
}
