package com.riffle.shared.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.riffle.core.domain.WakeLockPreferencesStore
import org.koin.compose.koinInject
import platform.UIKit.UIApplication

/**
 * iOS's idle timer, behind an interface so [ReaderWakeLock] can be tested without UIKit.
 */
internal interface IdleTimer {
    var disabled: Boolean
}

internal object UiKitIdleTimer : IdleTimer {
    override var disabled: Boolean
        get() = UIApplication.sharedApplication.idleTimerDisabled
        set(value) {
            UIApplication.sharedApplication.idleTimerDisabled = value
        }
}

/**
 * Reader-scoped "keep screen on", the iOS counterpart to Android's `FLAG_KEEP_SCREEN_ON` on the
 * reader window.
 *
 * The preference used to be applied as a side effect of its own setter, which made it both
 * app-wide (the screen stayed awake on the library and settings screens too) and non-durable
 * (nothing re-applied it at launch, so it silently lapsed after every cold start). Scoping it to
 * the reader fixes both: entering a book applies it, leaving always releases it (#1071 §15.3).
 */
internal class ReaderWakeLock(private val idleTimer: IdleTimer = UiKitIdleTimer) {
    fun enterReader(keepScreenOn: Boolean) {
        idleTimer.disabled = keepScreenOn
    }

    /** Always releases, whatever the preference — the reader is no longer on screen. */
    fun leaveReader() {
        idleTimer.disabled = false
    }
}

/** Applies [ReaderWakeLock] for as long as the calling reader screen is composed. */
@Composable
internal fun KeepReaderScreenOn(wakeLock: ReaderWakeLock = ReaderWakeLock()) {
    val store = koinInject<WakeLockPreferencesStore>()
    val keepScreenOn by store.keepScreenOn.collectAsState(initial = false)
    DisposableEffect(keepScreenOn) {
        wakeLock.enterReader(keepScreenOn)
        onDispose { wakeLock.leaveReader() }
    }
}
