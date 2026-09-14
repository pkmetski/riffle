package com.riffle.shared.library

import com.riffle.core.domain.ApplicationScope
import com.riffle.feature.library.DownloadManager
import com.riffle.feature.library.DownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * iOS port of Android's app-level DownloadManager. Runs work on ApplicationScope so downloads
 * survive navigation away from the screen that started them.
 */
internal class IosDownloadManagerImpl(
    applicationScope: ApplicationScope,
) : DownloadManager {

    private val scope = applicationScope.coroutineScope
    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    override val states: StateFlow<Map<String, DownloadState>> = _states
    private val jobs = mutableMapOf<String, Job>()

    override fun start(key: String, work: suspend (onProgress: (Long, Long) -> Unit) -> DownloadState) {
        if (_states.value[key] is DownloadState.InProgress) return
        set(key, DownloadState.InProgress())
        val job = scope.launch {
            val terminal = try {
                work { downloaded, total ->
                    set(key, DownloadState.InProgress(if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null))
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                DownloadState.NotDownloaded
            }
            jobs.remove(key)
            set(key, terminal)
        }
        jobs[key] = job
    }

    override fun startWithoutProgress(key: String, stateWhileRunning: DownloadState, work: suspend () -> DownloadState) {
        if (_states.value[key] is DownloadState.InProgress) return
        set(key, stateWhileRunning)
        scope.launch {
            val terminal = try {
                work()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                DownloadState.NotDownloaded
            }
            set(key, terminal)
        }
    }

    override fun cancel(key: String) {
        jobs.remove(key)?.cancel()
        _states.update { it - key }
    }

    override fun clear(key: String) {
        _states.update { it - key }
    }

    private fun set(key: String, state: DownloadState) {
        _states.update { it + (key to state) }
    }
}
