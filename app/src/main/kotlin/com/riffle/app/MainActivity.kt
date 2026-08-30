package com.riffle.app

import android.content.Intent
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import com.riffle.app.feature.reader.ReaderStateHolder
import com.riffle.app.i18n.AppLocaleController
import com.riffle.app.feature.reader.VolumeKeyAction
import com.riffle.app.feature.reader.VolumeKeyEventHandler
import com.riffle.app.feature.reader.VolumeNavEvent
import com.riffle.app.feature.reader.VolumeNavigationController
import com.riffle.app.feature.reader.autoscroll.AutoScrollController
import com.riffle.core.domain.autoscroll.AutoScrollEvent
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.autoscroll.isActive as isAutoScrollActive
import com.riffle.app.navigation.MainScreen
import com.riffle.app.playback.NowPlayingNavigator
import com.riffle.app.ui.BottomNavBarScrim
import com.riffle.app.ui.theme.RiffleTheme
import com.riffle.core.domain.VolumeKeyPreferencesStore
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.appearance.ResolvedAppearance
import androidx.compose.runtime.LaunchedEffect
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.KoinAndroidContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.lifecycleScope

class MainActivity : FragmentActivity() {

    private val volumeNavigationController: VolumeNavigationController by inject()
    private val readerStateHolder: ReaderStateHolder by inject()
    private val volumeKeyPreferencesStore: VolumeKeyPreferencesStore by inject()
    private val appearanceCoordinator: AppearanceCoordinator by inject()
    private val nowPlayingNavigator: NowPlayingNavigator by inject()
    private val autoScrollController: AutoScrollController by inject()

    private lateinit var volumeNavEnabled: StateFlow<Boolean>
    private lateinit var invertVolumeKeys: StateFlow<Boolean>

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleController.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) {
            // EpubNavigatorFragment and PdfiumNavigatorFragment require their respective
            // NavigatorFactory and have no public no-arg constructor. On Activity recreation
            // (rotation), FragmentManager.restoreSaveStateInternal() tries to reinstantiate
            // them with the DEFAULT factory before any Compose code runs — crashing with
            // Fragment$InstantiationException. Registering this safe factory first makes
            // restoration succeed with a plain Fragment placeholder; the reader screens'
            // AndroidView.update code then detects and replaces it via the proper factory.
            supportFragmentManager.fragmentFactory = SafeNavigatorFragmentFactory()
        }
        super.onCreate(savedInstanceState)
        volumeNavEnabled = volumeKeyPreferencesStore.volumeKeyNavigationEnabled
            .stateIn(lifecycleScope, SharingStarted.Eagerly, true)
        invertVolumeKeys = volumeKeyPreferencesStore.invertVolumeKeys
            .stateIn(lifecycleScope, SharingStarted.Eagerly, false)
        // Seed the coordinator's system-dark flag synchronously from the activity's current
        // Configuration so the very first composition resolves the correct chrome — the
        // LaunchedEffect below only fires *after* the first frame and would otherwise let a
        // dark-OS device render one frame of light chrome.
        val nightMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        appearanceCoordinator.setSystemDark(nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        // Both system bars are fully transparent at the OS level. The app draws its own
        // scrim under the nav-bar inset area (BottomNavBarScrim, applied globally in
        // setContent below) so the look is identical across gesture-nav devices (where
        // Android forces navigationBarColor transparent regardless of what we pass) and
        // 3-button-nav devices. enableEdgeToEdge already sets both bars'
        // contrast-enforced flag to false on API 29+, so nothing needs to be set here on
        // top of it (the previous explicit `window.isStatusBarContrastEnforced = false`
        // and its nav-bar counterpart are deprecated with no replacement — the OS now
        // handles this automatically once we opt into edge-to-edge).
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(transparent, transparent),
            navigationBarStyle = SystemBarStyle.auto(transparent, transparent),
        )
        handleIntent(intent)
        setContent {
            // Koin and Hilt run side by side during the migration: composables converted to
            // koinViewModel()/koinInject() resolve through this context while the rest still
            // resolve through Hilt.
            KoinAndroidContext {
                // Feed reactive OS dark-mode toggles into the coordinator so its `resolved`
                // StateFlow stays in step with the system theme without polling.
                val systemDark = isSystemInDarkTheme()
                LaunchedEffect(systemDark) { appearanceCoordinator.setSystemDark(systemDark) }
                val appearance: ResolvedAppearance by appearanceCoordinator.resolved.collectAsState()
                val isDark = appearance.appChrome.isDark
                // Keep the transparent status-bar icon contrast in step with the chosen chrome theme,
                // overriding the system-driven default from enableEdgeToEdge above (otherwise a forced
                // Dark theme under a Light OS would render dark icons on the dark top app bar).
                // The navigation bar always sits over our dark BottomNavBarScrim, so its icons must
                // stay light regardless of theme — otherwise light-mode renders dark-on-dark and the
                // gesture pill / 3-button nav vanishes.
                val view = LocalView.current
                SideEffect {
                    WindowCompat.getInsetsController(window, view).run {
                        isAppearanceLightStatusBars = !isDark
                        isAppearanceLightNavigationBars = false
                    }
                }
                RiffleTheme(darkTheme = isDark) {
                    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
                    val windowSizeClass = calculateWindowSizeClass(this)
                    Box(Modifier.fillMaxSize()) {
                        MainScreen(windowSizeClass = windowSizeClass)
                        BottomNavBarScrim(modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }

    private val consumedVolumeKeyCodes = mutableSetOf<Int>()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            return super.onKeyDown(keyCode, event)
        }
        val isVolumeDown = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        val action = VolumeKeyEventHandler.handle(
            isVolumeDown = isVolumeDown,
            isReaderActive = readerStateHolder.isReaderActive,
            volumeNavEnabled = volumeNavEnabled.value,
            invertVolumeKeys = invertVolumeKeys.value,
            isPanelOpen = readerStateHolder.isPanelOpen,
            isAudioPlaying = readerStateHolder.isAudioPlaying,
            isAutoScrolling = autoScrollController.state.value.isAutoScrollActive,
            volumeUpPointsRight = when (currentDisplayRotation()) {
                Surface.ROTATION_270 -> true
                Surface.ROTATION_90 -> false
                else -> null
            },
        )
        return when (action) {
            VolumeKeyAction.NavigateForward -> {
                consumedVolumeKeyCodes.add(keyCode)
                volumeNavigationController.emit(VolumeNavEvent.Forward)
                true
            }
            VolumeKeyAction.NavigateBackward -> {
                consumedVolumeKeyCodes.add(keyCode)
                volumeNavigationController.emit(VolumeNavEvent.Backward)
                true
            }
            VolumeKeyAction.AutoScrollFaster -> {
                consumedVolumeKeyCodes.add(keyCode)
                autoScrollController.dispatch(AutoScrollEvent.NudgeSpeed(by = AutoScrollSpeed.STEP_WPM))
                true
            }
            VolumeKeyAction.AutoScrollSlower -> {
                consumedVolumeKeyCodes.add(keyCode)
                autoScrollController.dispatch(AutoScrollEvent.NudgeSpeed(by = -AutoScrollSpeed.STEP_WPM))
                true
            }
            VolumeKeyAction.Swallow -> {
                consumedVolumeKeyCodes.add(keyCode)
                true
            }
            VolumeKeyAction.PassThrough -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (consumedVolumeKeyCodes.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    // Reused instance (singleTop) — the media-notification tap arrives here while the app is running.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * `Context.display` is the modern API but only exists on API 30+; below that we fall back to
     * `windowManager.defaultDisplay`, the only source of the current rotation on API 24-29. The
     * suppression is scoped to that legacy branch — the deprecation warning is genuine but there
     * is no non-deprecated API on those platforms.
     */
    private fun currentDisplayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

    /** Routes a media-notification tap to the active player; [MainScreen] reads NowPlayingStore. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_NOW_PLAYING) {
            nowPlayingNavigator.requestOpen()
            // The activity retains its launch intent, so consume the action — otherwise a later
            // recreation (e.g. rotation) would re-fire this and yank the user back to the player.
            intent.action = null
        }
    }

    companion object {
        const val ACTION_OPEN_NOW_PLAYING = "com.riffle.app.action.OPEN_NOW_PLAYING"
    }
}

// Readium navigator fragments (EpubNavigatorFragment, PdfiumNavigatorFragment) have no
// public no-arg constructor and must be created via their respective NavigatorFactory.
// The default FragmentFactory uses reflection and cannot instantiate them, so
// FragmentManager.restoreSaveStateInternal() throws InstantiationException on Activity
// recreation (e.g. rotation) before Compose code has a chance to register the proper factory.
// This safe wrapper returns a plain Fragment placeholder for any class that can't be
// reflectively instantiated; the reader screens replace the placeholder immediately.
private class SafeNavigatorFragmentFactory : FragmentFactory() {
    override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
        // All org.readium fragments are only valid when created through their NavigatorFactory.
        // A default-factory instance (via reflection) either fails (no no-arg constructor) or
        // succeeds but leaves the fragment without required dependencies — crashing in
        // onViewCreated (e.g. PdfiumDocumentFragment.reset()). Replace every Readium fragment
        // from saved state with a view-providing placeholder; reader screens detect and
        // replace it via their factory once Compose starts.
        if (className.startsWith("org.readium.")) return NavigatorPlaceholderFragment()
        return try {
            super.instantiate(classLoader, className)
        } catch (_: Fragment.InstantiationException) {
            NavigatorPlaceholderFragment()
        }
    }
}

class NavigatorPlaceholderFragment : Fragment() {
    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        android.widget.FrameLayout(requireContext())
}
