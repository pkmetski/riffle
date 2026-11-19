package com.riffle.core.data.dictionary

interface JsonlToSqliteConverter {
    suspend fun convert(
        jsonlPath: String,
        dbPath: String,
        onProgress: (processed: Long, total: Long) -> Unit = { _, _ -> },
    )
}
