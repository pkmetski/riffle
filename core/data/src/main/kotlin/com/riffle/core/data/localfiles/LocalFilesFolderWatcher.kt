package com.riffle.core.data.localfiles

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.SourceRepository
import com.riffle.core.models.SourceType
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the LocalFiles library in sync with the folders on disk *without* asking the user to hit a
 * "Rescan" button. Two mechanisms, complementary:
 *
 * 1. **App-foreground kick**: on every [androidx.lifecycle.Lifecycle.Event.ON_START] we run a
 *    scan. Deterministic — covers the "user closed the app, dropped a book in, reopened" case
 *    regardless of whether the DocumentsProvider dispatches change notifications.
 * 2. **SAF `ContentObserver`**: while the app is running, we register a [ContentObserver] on each
 *    configured folder's children URI so a book added via the Files app (or any SAF-aware app)
 *    triggers a scan within `DEBOUNCE_MS`. Providers vary in how faithfully they fire these — the
 *    lifecycle kick above is the safety net.
 *
 * Scans are idempotent (identity-hash keyed) so triggering more often than strictly necessary is
 * cheap: unchanged rows only touch `lastSeenAtEpochMs`.
 */
@Singleton
class LocalFilesFolderWatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sourceRepository: SourceRepository,
    private val folderDao: LocalFilesFolderDao,
    private val scanner: LocalFilesScanner,
    private val applicationScope: ApplicationScope,
    private val logger: Logger,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableMapOf<String, ContentObserver>()
    private var scanDebounceJob: Job? = null
    private var startedFor: String? = null
    private var currentSourceId: String? = null

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // Foreground return: catches file drops made while the app was backgrounded/closed.
            currentSourceId?.let { requestScan(it, reason = "lifecycle.onStart") }
        }
    }

    /**
     * Begin watching. Idempotent: safe to call more than once. Registers a lifecycle observer on
     * [ProcessLifecycleOwner] and collects the LocalFiles source id + its folder set for the
     * lifetime of [applicationScope].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (startedFor != null) return
        startedFor = "started"
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        applicationScope.launchSurvivable {
            sourceRepository.observeAll()
                .map { list -> list.firstOrNull { it.type == SourceType.LOCAL_FILES }?.id }
                .distinctUntilChanged()
                .collectLatest { sourceId ->
                    currentSourceId = sourceId
                    if (sourceId == null) {
                        unregisterAll()
                        return@collectLatest
                    }
                    requestScan(sourceId, reason = "source-appeared")
                    folderDao.observeForSource(sourceId).collect { folders ->
                        syncObservers(folders.map { it.treeUri }, sourceId)
                    }
                }
        }
    }

    private fun syncObservers(currentTreeUris: List<String>, sourceId: String) {
        val currentSet = currentTreeUris.toSet()
        val removed = observers.keys - currentSet
        for (tree in removed) {
            observers.remove(tree)?.let { context.contentResolver.unregisterContentObserver(it) }
            logger.d(LogChannel.LocalFiles) { "watcher unregistered observer tree=$tree" }
        }
        for (tree in currentSet - observers.keys) {
            val treeUri = try { Uri.parse(tree) } catch (_: Throwable) { continue }
            val childrenUri = try {
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
            } catch (t: Throwable) {
                logger.d(LogChannel.LocalFiles, t) { "watcher can't build childrenUri for tree=$tree" }
                continue
            }
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    logger.d(LogChannel.LocalFiles) { "watcher content change tree=$tree uri=$uri" }
                    requestScan(sourceId, reason = "content-observer")
                }
            }
            try {
                context.contentResolver.registerContentObserver(childrenUri, true, observer)
                observers[tree] = observer
                logger.d(LogChannel.LocalFiles) { "watcher registered observer tree=$tree" }
            } catch (t: Throwable) {
                logger.d(LogChannel.LocalFiles, t) { "watcher registerContentObserver failed tree=$tree" }
            }
        }
    }

    private fun requestScan(sourceId: String, reason: String) {
        scanDebounceJob?.cancel()
        scanDebounceJob = applicationScope.launchSurvivable {
            delay(DEBOUNCE_MS)
            logger.d(LogChannel.LocalFiles) { "watcher auto-scan reason=$reason" }
            try {
                scanner.scan(sourceId)
            } catch (t: Throwable) {
                logger.d(LogChannel.LocalFiles, t) { "watcher auto-scan failed" }
            }
        }
    }

    private fun unregisterAll() {
        for ((_, observer) in observers) {
            context.contentResolver.unregisterContentObserver(observer)
        }
        observers.clear()
    }

    companion object {
        // Content observers can fire in bursts (a copy operation dispatches per-file); coalesce
        // them into a single scan.
        private const val DEBOUNCE_MS: Long = 500L
    }
}
