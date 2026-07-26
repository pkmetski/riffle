package com.riffle.core.sync

import com.riffle.core.sources.webdav.AnnotationSyncException

/**
 * The outcome of one annotation-sync cycle (live push or sweep). The store keeps only the latest;
 * persisting across process death is rejected by ADR 0036.
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

/** Classify a thrown exception into a [CycleOutcome.Failed] subtype. */
fun Throwable.toFailedCycleOutcome(at: Long): CycleOutcome.Failed = when (this) {
    is AnnotationSyncException.AuthFailed -> CycleOutcome.Failed.Auth(at, code)
    is AnnotationSyncException.HttpFailure -> CycleOutcome.Failed.Server(at, code)
    is AnnotationSyncException.NetworkError -> CycleOutcome.Failed.Network(at, message)
    is AnnotationSyncException.TlsError -> CycleOutcome.Failed.Tls(at, message)
    else -> CycleOutcome.Failed.Unknown(at, message)
}
