package com.riffle.core.data

import com.riffle.core.common.Clock
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

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
     * Installs the hook. Kotlin/Native allows the hook to be set once per process, so a second
     * call is ignored — safe to call from app startup without guarding at the call site.
     */
    fun install(repository: IosCrashReportRepositoryImpl, clock: Clock) {
        if (installed) return
        installed = true
        val previous = setUnhandledExceptionHook { throwable ->
            runCatching {
                val timestamp = clock.nowMs()
                repository.record(id = "crash-$timestamp", content = report(throwable, timestamp))
            }
        }
        // Chain rather than swallow: whatever the runtime (or a prior hook) did on an unhandled
        // exception must still happen, or a crash would be silently downgraded to a log line.
        previous?.let { chained ->
            setUnhandledExceptionHook { throwable ->
                runCatching {
                    repository.record(
                        id = "crash-${clock.nowMs()}",
                        content = report(throwable, clock.nowMs()),
                    )
                }
                chained(throwable)
            }
        }
    }

    private fun report(throwable: Throwable, timestampMillis: Long): String = buildString {
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

    private var installed = false
}
