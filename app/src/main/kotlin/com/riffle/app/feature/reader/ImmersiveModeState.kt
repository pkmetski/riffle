package com.riffle.app.feature.reader

import android.os.Build
import android.view.View
import android.view.Window
import androidx.activity.compose.LocalActivity
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.riffle.feature.reader.ImmersiveModeState
import com.riffle.feature.reader.SystemBarsController

@Suppress("DEPRECATION")
private class WindowInsetsBarsController(
    private val window: Window,
    private val delegate: WindowInsetsControllerCompat,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : SystemBarsController {
    override fun hide() = delegate.hide(WindowInsetsCompat.Type.systemBars())
    override fun show() = delegate.show(WindowInsetsCompat.Type.systemBars())
    override fun applyImmersiveBehavior() {
        delegate.systemBarsBehavior = immersiveSystemBarsBehavior(sdkInt)
    }

    override fun reapplyRaw() {
        if (sdkInt >= Build.VERSION_CODES.R) {
            delegate.hide(WindowInsetsCompat.Type.systemBars())
            return
        }
        val decor = window.decorView
        val appearanceMask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val appearance = decor.systemUiVisibility and appearanceMask
        val immersiveFlags = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val layoutOnly = View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        decor.systemUiVisibility = layoutOnly or appearance
        decor.post { decor.systemUiVisibility = immersiveFlags or appearance }
    }
}

@VisibleForTesting
@Suppress("UNUSED_PARAMETER")
internal fun immersiveSystemBarsBehavior(sdkInt: Int): Int =
    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

@Composable
fun rememberImmersiveModeState(): ImmersiveModeState {
    val window = checkNotNull(LocalActivity.current).window
    val controller = remember(window) {
        WindowInsetsBarsController(window, WindowInsetsControllerCompat(window, window.decorView))
    }

    var savedIsImmersive by rememberSaveable { mutableStateOf(true) }

    val state = remember(controller) {
        ImmersiveModeState(controller, clock = android.os.SystemClock::elapsedRealtime, initialIsImmersive = savedIsImmersive)
    }

    SideEffect {
        state.onUserImmersiveChanged = { savedIsImmersive = it }
    }

    LaunchedEffect(state) {
        if (savedIsImmersive) state.hide()
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentSavedIsImmersive by rememberUpdatedState(savedIsImmersive)
    val lastResumeMs = remember { mutableStateOf(0L) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lastResumeMs.value = android.os.SystemClock.elapsedRealtime()
                if (currentSavedIsImmersive) {
                    state.hide(force = true)
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val density = LocalDensity.current
    val topInset = WindowInsets.systemBars.getTop(density)
    val prevTopInset = remember { mutableStateOf(topInset) }
    LaunchedEffect(topInset) {
        val wasHidden = prevTopInset.value == 0
        prevTopInset.value = topInset
        if (topInset > 0 && wasHidden) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (lastResumeMs.value != 0L && now - lastResumeMs.value < ImmersiveModeState.RESUME_SUPPRESSION_MS) {
                return@LaunchedEffect
            }
            state.onBarsRestoredExternally()
        }
    }

    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(state, windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }
            .collect { focused -> state.onWindowFocusChanged(focused) }
    }

    DisposableEffect(state) {
        onDispose { state.show() }
    }

    return state
}
