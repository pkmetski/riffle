package com.riffle.feature.library

interface ReadaloudOfflineDownloader {
    suspend fun download(
        storytellerSourceId: String,
        storytellerBookId: String,
        onProgress: (Float) -> Unit,
    ): Boolean?
}
