package com.riffle.core.data.dictionary

import com.riffle.core.database.createDictSqliteFromJsonl

internal class IosJsonlToSqliteConverter : JsonlToSqliteConverter {
    override suspend fun convert(
        jsonlPath: String,
        dbPath: String,
        onProgress: (processed: Long, total: Long) -> Unit,
    ) = createDictSqliteFromJsonl(jsonlPath, dbPath, onProgress)
}
