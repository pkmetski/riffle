package com.riffle.core.dictionary

import kotlinx.coroutines.flow.Flow

interface DictionaryRepository {
    suspend fun lookup(form: String, languageTag: String): List<DictionaryEntry>
    suspend fun recordLookup(form: String, languageTag: String)
    fun observeRecentLookups(languageTag: String, limit: Int = 10): Flow<List<String>>
}
