package com.riffle.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The outcome of one annotation-sync cycle (live push or sweep). The store keeps only the latest;
 * persisting across process death is rejected by ADR 0036 — the next app-start sweep overwrites
 * the value anyway.
 */
sealed class CycleOutcome {
    /** Initial state — no cycle has run since this process started. */
    object NeverRun : CycleOutcome()

    data class Success(val atMs: Long) : CycleOutcome()

    sealed class Failed(open val atMs: Long) : CycleOutcome() {
        data class Network(override val atMs: Long, val message: String?) : Failed(atMs)
        data class Auth(override val atMs: Long, val code: Int) : Failed(atMs)
        data class Tls(override val atMs: Long, val message: String?) : Failed(atMs)
        data class Server(override val atMs: Long, val code: Int) : Failed(atMs)
        data class Unknown(override val atMs: Long, val message: String?) : Failed(atMs)
    }
}

/**
 * In-memory observable of the last annotation-sync cycle outcome. Both the live
 * [AnnotationSyncController] and the [AnnotationSweep] report through this so the UI sees one
 * unified state.
 */
@Singleton
class AnnotationSyncStatusStore @Inject constructor() {
    private val _lastCycleOutcome = MutableStateFlow<CycleOutcome>(CycleOutcome.NeverRun)
    val lastCycleOutcome: StateFlow<CycleOutcome> = _lastCycleOutcome.asStateFlow()

    fun report(outcome: CycleOutcome) {
        _lastCycleOutcome.value = outcome
    }
}

/**
 * Classify a thrown exception into a [CycleOutcome.Failed] subtype. Single mapper used by both the
 * live controller and the sweep so identical errors classify the same way.
 */
fun Throwable.toFailedCycleOutcome(at: Long): CycleOutcome.Failed = when (this) {
    is AnnotationSyncException.AuthFailed -> CycleOutcome.Failed.Auth(at, code)
    is AnnotationSyncException.HttpFailure -> CycleOutcome.Failed.Server(at, code)
    is AnnotationSyncException.NetworkError -> CycleOutcome.Failed.Network(at, message)
    is AnnotationSyncException.TlsError -> CycleOutcome.Failed.Tls(at, message)
    else -> CycleOutcome.Failed.Unknown(at, message)
}
