package com.riffle.core.domain.localfiles

interface LocalFilesScannerInterface {
    suspend fun scan(sourceId: String)
}
