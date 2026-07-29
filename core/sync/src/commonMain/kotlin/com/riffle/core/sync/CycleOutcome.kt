package com.riffle.core.sync

/**
 * The outcome of one annotation-sync cycle (live push or sweep). The store keeps only the latest;
 * persisting across process death is rejected by ADR 0043.
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
