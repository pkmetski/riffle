package com.riffle.core.sync

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory observable of the last annotation-sync cycle outcome. Both the live
 * AnnotationSyncController and the AnnotationSweep report through this so the UI sees one
 * unified state.
 */
@Singleton
class AnnotationSyncStatusStore @Inject constructor() {
    private val _lastCycleOutcome = MutableStateFlow<CycleOutcome>(CycleOutcome.NeverRun)
    val lastCycleOutcome: StateFlow<CycleOutcome> = _lastCycleOutcome.asStateFlow()

    private val _lastSuccessAtMs = MutableStateFlow<Long?>(null)
    /**
     * Timestamp of the last cycle that actually completed. Sticks across subsequent failures,
     * which is what the UI's "Last sync" relative time needs.
     */
    val lastSuccessAtMs: StateFlow<Long?> = _lastSuccessAtMs.asStateFlow()

    fun report(outcome: CycleOutcome) {
        _lastCycleOutcome.value = outcome
        if (outcome is CycleOutcome.Success) _lastSuccessAtMs.value = outcome.atMs
    }
}
