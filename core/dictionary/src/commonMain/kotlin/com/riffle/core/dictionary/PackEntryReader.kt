package com.riffle.core.dictionary

interface PackEntryReader {
    fun query(form: String): List<DictionaryEntry>
}
