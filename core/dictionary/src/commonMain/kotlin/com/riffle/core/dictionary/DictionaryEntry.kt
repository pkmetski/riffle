package com.riffle.core.dictionary

data class DictionaryEntry(
    val form: String,
    val partOfSpeech: String,
    val glosses: List<String>,
)
