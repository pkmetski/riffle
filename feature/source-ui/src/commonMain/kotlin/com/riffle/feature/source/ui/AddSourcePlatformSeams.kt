package com.riffle.feature.source.ui

import com.riffle.core.domain.AnnotationSyncConfig

/**
 * Outcome of probing a WebDAV annotation-sync target, mirroring
 * `com.riffle.core.sources.webdav.TestConnectionResult` one branch for one branch, plus
 * [UnparseableUrl] for the case where the factory could not build a target at all (previously
 * signalled by the factory returning `null`).
 */
sealed interface WebdavTestOutcome {
    data object Success : WebdavTestOutcome
    data object AuthFailed : WebdavTestOutcome

    /** The URL could not be turned into a target — same copy as the old `factory == null` path. */
    data object UnparseableUrl : WebdavTestOutcome
    data class InvalidUrl(val message: String) : WebdavTestOutcome
    data class NetworkError(val message: String) : WebdavTestOutcome
    data class TlsError(val message: String) : WebdavTestOutcome

    /** HTTP status failure. Named `HttpError`, not `ServerError`: ADR 0049's Source/Service
     *  taxonomy bans new `Server*` identifiers (see `checkNoServerReferences`). */
    data class HttpError(val code: Int) : WebdavTestOutcome
}

/**
 * Probes a WebDAV annotation-sync target. The real implementation wraps
 * `WebDavAnnotationSyncTargetFactory`, which lives in `core/sources/src/jvmMain` and therefore
 * cannot be referenced from this module's `commonMain` (an android+ios source set). Android binds
 * the real factory; iOS binds a stub because the WebDAV sidecar screen is not reachable there.
 */
fun interface WebdavConnectionTester {
    suspend fun test(config: AnnotationSyncConfig): WebdavTestOutcome
}

/**
 * Kicks the progress-sync sweep immediately after a WebDAV config is saved. On Android this is
 * `ProgressSyncScheduler.sweepNow(context)` (WorkManager); iOS has no WorkManager equivalent and
 * binds a no-op. Replaces the `android.content.Context` the ViewModel used to hold solely to
 * reach that scheduler.
 */
fun interface ProgressSyncTrigger {
    fun sweepNow()
}

/**
 * Debug-build convenience prefill for the Add-Source form, previously read straight off
 * `com.riffle.app.BuildConfig.DEV_SERVER_URL / DEV_USERNAME / DEV_PASSWORD` (populated from
 * `local.properties` in debug builds and empty in release). Android supplies the BuildConfig
 * values; iOS and tests use [Empty].
 */
data class DevSourceDefaults(
    val url: String = "",
    val username: String = "",
    val password: String = "",
) {
    companion object {
        val Empty = DevSourceDefaults()
    }
}
