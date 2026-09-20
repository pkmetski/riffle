package com.riffle.core.data

import com.riffle.core.common.Clock
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException

/**
 * Writes a crash report when an unhandled Kotlin exception reaches the top of a coroutine or
 * thread — the iOS counterpart to Android's `FileCrashReportSender`, which hooks
 * `Thread.UncaughtExceptionHandler`.
 *
 * Without this, [IosCrashReportRepositoryImpl] would list an always-empty directory and the
 * Settings "crash reports" surface would be dead on iOS.
 *
 * Note this covers Kotlin-side unhandled exceptions only. A Swift/Obj-C crash or a signal
 * (SIGSEGV etc.) terminates the process before any Kotlin hook runs; capturing those needs a
 * native crash reporter and is out of scope here.
 */
@OptIn(ExperimentalNativeApi::class)
object IosCrashReportRecorder {

    /**
     * Installs the hook. Guarded by [installed], so a second call is a no-op and the call site
     * needs no guard of its own. (Kotlin/Native itself does *not* refuse a second
     * `setUnhandledExceptionHook`; it replaces the current one and hands the old one back.)
     */
    fun install(repository: IosCrashReportRepositoryImpl, clock: Clock) {
        if (installed) return
        installed = true
        previousHook = setUnhandledExceptionHook { throwable ->
            onUnhandled(
                throwable = throwable,
                record = { t ->
                    val timestamp = clock.nowMs()
                    repository.record(id = "crash-$timestamp", content = report(t, timestamp))
                },
                chain = previousHook,
                terminate = { t -> terminateWithUnhandledException(t) },
            )
        }
    }

    private var installed = false
    private var previousHook: ReportUnhandledExceptionHook? = null

    internal fun report(throwable: Throwable, timestampMillis: Long): String = buildString {
        appendLine("Riffle iOS crash report")
        appendLine("timestamp: $timestampMillis")
        appendLine("type: ${throwable::class.simpleName ?: "Throwable"}")
        appendLine("message: ${throwable.message ?: "(none)"}")
        appendLine()
        appendLine(throwable.stackTraceToString())
        var cause = throwable.cause
        while (cause != null) {
            appendLine()
            appendLine("Caused by: ${cause::class.simpleName ?: "Throwable"}: ${cause.message ?: "(none)"}")
            appendLine(cause.stackTraceToString())
            cause = cause.cause
        }
    }
}

/**
 * What the installed hook does, lifted out of [IosCrashReportRecorder.install] so it can be
 * driven by a test without touching the process-wide hook.
 *
 * **A crash must stay a crash.** In Kotlin/Native an installed hook *replaces* the runtime's
 * `terminateWithUnhandledException`, so a hook that records and returns normally downgrades the
 * crash to a log line and leaves the app running in whatever state the exception left it. The
 * original version of this file only chained `if (previous != null)` — and in a normal app there
 * is no previous hook, so the branch actually taken was the swallowing one.
 *
 * Recording is therefore best-effort ([record] failing must not stop termination), and the
 * default processing always runs afterwards: the prior hook if one existed, otherwise
 * [terminate].
 */
internal fun onUnhandled(
    throwable: Throwable,
    record: (Throwable) -> Unit,
    chain: ((Throwable) -> Unit)?,
    terminate: (Throwable) -> Unit,
) {
    runCatching { record(throwable) }
    if (chain != null) chain(throwable) else terminate(throwable)
}
