package com.riffle.feature.library

import com.riffle.core.domain.DispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns in-flight downloads on an application-scoped [CoroutineScope] so they survive navigation
 * away from the screen that started them — the detail screen's ViewModel (and its `viewModelScope`)
 * is cleared on back, which previously cancelled the download mid-transfer. State is keyed by an
 * opaque string so the originating screen, or a freshly recreated one, can observe progress and
 * completion.
 *
 * Shared by both platforms: Android's `app`-module `DownloadManager` and iOS's
 * `IosDownloadManagerImpl` are thin delegating shells over this class. Before it existed the iOS
 * port had drifted — its `startWithoutProgress` only rejected a duplicate when the visible state
 * happened to be [DownloadState.InProgress], so a second call with a `stateWhileRunning` of
 * [DownloadState.Cached] re-ran the work while the first run was still going.
 *
 * Mutation of the two bookkeeping maps goes through [MutableStateFlow.getAndUpdate] /
 * [MutableStateFlow.update], whose compare-and-set loop gives the same atomicity a `synchronized`
 * block used to, without a JVM-only primitive.
 */
class DefaultDownloadManager(
    private val scope: CoroutineScope,
) : DownloadManager {

    /**
     * Convenience for hosts that have no application scope to hand (the iOS XCTest suite builds
     * one of these directly). Downloads started through it live as long as the manager does.
     *
     * Takes the [DispatcherProvider] rather than touching `Dispatchers.Default`, per the seam
     * this codebase routes every dispatcher through.
     */
    constructor(dispatchers: DispatcherProvider) :
        this(CoroutineScope(SupervisorJob() + dispatchers.default))

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    override val states: StateFlow<Map<String, DownloadState>> = _states

    /** Keys with a silent (progress-less) run in flight — their visible state is not InProgress. */
    private val silentKeys = MutableStateFlow<Set<String>>(emptySet())
    private val jobs = MutableStateFlow<Map<String, Job>>(emptyMap())

    /**
     * Starts [work] for [key] on the application scope unless a download for [key] is already in
     * progress (idempotent — a duplicate tap is a no-op). [work] receives a progress callback and
     * returns the terminal [DownloadState].
     */
    override fun start(key: String, work: suspend (onProgress: (Long, Long) -> Unit) -> DownloadState) {
        if (_states.value[key] is DownloadState.InProgress) return
        set(key, DownloadState.InProgress())
        val job = scope.launch {
            val terminal = try {
                work { downloaded, total ->
                    set(key, DownloadState.InProgress(if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null))
                }
            } catch (e: Throwable) {
                // A repo that lets something escape must not leave the key stuck on a spinner.
                // Catches Error subclasses (e.g. OutOfMemoryError) that Exception misses.
                if (e is CancellationException) throw e
                DownloadState.NotDownloaded
            }
            jobs.update { it - key }
            set(key, terminal)
        }
        jobs.update { it + (key to job) }
    }

    /**
     * Runs local promotion work in the same app scope as network downloads, but keeps the visible
     * state stable until the terminal state arrives. Cached-to-downloaded promotion should not look
     * like a fresh download.
     */
    override fun startWithoutProgress(
        key: String,
        stateWhileRunning: DownloadState,
        work: suspend () -> DownloadState,
    ) {
        if (_states.value[key] is DownloadState.InProgress) return
        // getAndUpdate is a CAS loop: only the caller that observed `key` absent proceeds.
        if (key in silentKeys.getAndUpdate { it + key }) return
        set(key, stateWhileRunning)
        scope.launch {
            val terminal = try {
                work()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                DownloadState.NotDownloaded
            } finally {
                silentKeys.update { it - key }
            }
            set(key, terminal)
        }
    }

    /** Cancels an in-flight download for [key] and clears its state. */
    override fun cancel(key: String) {
        val job = jobs.value[key]
        jobs.update { it - key }
        job?.cancel()
        _states.update { it - key }
    }

    /** Drops any tracked state for [key], e.g. after the user removes the download. */
    override fun clear(key: String) {
        _states.update { it - key }
    }

    private fun set(key: String, state: DownloadState) {
        _states.update { it + (key to state) }
    }
}
