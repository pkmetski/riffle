package com.riffle.feature.library

import kotlinx.coroutines.flow.StateFlow

interface DownloadManager {
    val states: StateFlow<Map<String, DownloadState>>
    fun start(key: String, work: suspend (onProgress: (Long, Long) -> Unit) -> DownloadState)
    fun startWithoutProgress(key: String, stateWhileRunning: DownloadState, work: suspend () -> DownloadState)
    fun cancel(key: String)
    fun clear(key: String)
}
