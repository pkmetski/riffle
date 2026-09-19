package com.riffle.feature.library

import com.riffle.core.catalog.CatalogImportPhase
import com.riffle.core.catalog.CatalogImportProgress
import com.riffle.core.catalog.CatalogImportResult
import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger
import com.riffle.core.logging.NoopLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Application-scoped owner for uploads started from a detail screen. */
class BookImportManagerImpl constructor(
    private val scope: CoroutineScope,
    private val logger: Logger = NoopLogger,
) : BookImportManager {
    private val _states = MutableStateFlow<Map<String, BookImportState>>(emptyMap())
    override val states: StateFlow<Map<String, BookImportState>> = _states
    private val activeKeys = mutableSetOf<String>()
    // Was ConcurrentHashMap.newKeySet on Android. start() is only ever called from the UI
    // dispatcher and the claim callback runs inside the launched job, so a plain set matches
    // the previous behaviour on both platforms.
    private val claimedItemIds = mutableSetOf<String>()

    override fun start(
        key: String,
        work: suspend (onProgress: (CatalogImportProgress) -> Unit, claimItem: (String) -> Boolean) -> CatalogImportResult,
    ) {
        if (!activeKeys.add(key)) return
        logger.d(LogChannel.BookImport) { "start key=$key" }
        set(key, BookImportState.InProgress(CatalogImportPhase.Preparing))
        scope.launch {
            var uploadAccepted = false
            var claimedItemId: String? = null
            try {
                when (val result = work(
                    { progress ->
                        logger.d(LogChannel.BookImport) {
                            "progress key=$key phase=${progress.phase} files=${progress.completedFiles}/${progress.totalFiles}"
                        }
                        if (progress.phase == CatalogImportPhase.Uploaded) {
                            uploadAccepted = true
                            // The server has accepted the files. Reconciliation and metadata/progress
                            // enrichment continue in the application scope, but must not keep the
                            // detail screen looking as if the upload itself is still pending.
                            set(key, BookImportState.Completed)
                        } else if (!uploadAccepted) {
                            set(
                                key,
                                BookImportState.InProgress(
                                    phase = progress.phase,
                                    completedFiles = progress.completedFiles,
                                    totalFiles = progress.totalFiles,
                                ),
                            )
                        }
                    },
                    { id -> claimedItemIds.add(id).also { claimed -> if (claimed) claimedItemId = id } },
                )) {
                    is CatalogImportResult.Uploaded -> {
                        logger.d(LogChannel.BookImport) {
                            "completed key=$key destination=${result.destinationItemId} warnings=${result.warnings.size}"
                        }
                        set(key, BookImportState.Completed)
                    }
                    is CatalogImportResult.Failed -> {
                        logger.e(LogChannel.BookImport, result.cause) {
                            "failed key=$key message=${result.cause.message}"
                        }
                        if (!uploadAccepted) {
                            set(key, BookImportState.Failed(result.cause.message ?: "Upload failed"))
                        }
                    }
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                logger.e(LogChannel.BookImport, cause) { "crashed key=$key message=${cause.message}" }
                if (!uploadAccepted) {
                    set(key, BookImportState.Failed(cause.message ?: "Upload failed"))
                }
            } finally {
                activeKeys.remove(key)
                claimedItemId?.let { claimedItemIds.remove(it) }
            }
        }
    }

    fun clear(key: String) {
        _states.update { it - key }
    }

    private fun set(key: String, state: BookImportState) {
        _states.update { it + (key to state) }
    }
}
