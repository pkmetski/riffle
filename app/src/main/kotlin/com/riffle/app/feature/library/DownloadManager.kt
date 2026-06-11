package com.riffle.app.feature.library

import com.riffle.app.di.DownloadScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns in-flight downloads on an application-scoped [CoroutineScope] so they survive navigation away
 * from the screen that started them — the detail screen's ViewModel (and its `viewModelScope`) is
 * cleared on back, which previously cancelled the download mid-transfer. State is keyed by an opaque
 * string so the originating screen, or a freshly recreated one, can observe progress and completion.
 */
@Singleton
class DownloadManager @Inject constructor(
    @DownloadScope private val scope: CoroutineScope,
) {
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    /**
     * Starts [work] for [key] on the application scope unless a download for [key] is already in
     * progress (idempotent — a duplicate tap is a no-op). [work] receives a progress callback and
     * returns the terminal [DownloadState].
     */
    fun start(key: String, work: suspend (onProgress: (Long, Long) -> Unit) -> DownloadState) {
        if (_states.value[key] is DownloadState.InProgress) return
        set(key, DownloadState.InProgress())
        scope.launch {
            val terminal = try {
                work { downloaded, total ->
                    set(key, DownloadState.InProgress(downloadPercent(downloaded, total)))
                }
            } catch (e: Exception) {
                // A repo that lets something escape must not leave the key stuck on a spinner.
                if (e is kotlinx.coroutines.CancellationException) throw e
                DownloadState.NotDownloaded
            }
            set(key, terminal)
        }
    }

    /** Drops any tracked state for [key], e.g. after the user removes the download. */
    fun clear(key: String) {
        _states.update { it - key }
    }

    private fun set(key: String, state: DownloadState) {
        _states.update { it + (key to state) }
    }
}
