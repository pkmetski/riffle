package com.riffle.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import com.riffle.app.feature.reader.ReaderStateHolder
import com.riffle.app.feature.reader.VolumeKeyAction
import com.riffle.app.feature.reader.VolumeKeyEventHandler
import com.riffle.app.feature.reader.VolumeNavEvent
import com.riffle.app.feature.reader.VolumeNavigationController
import com.riffle.app.navigation.MainScreen
import com.riffle.app.ui.theme.RiffleTheme
import com.riffle.core.domain.VolumeKeyPreferencesStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.lifecycleScope
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var volumeNavigationController: VolumeNavigationController
    @Inject lateinit var readerStateHolder: ReaderStateHolder
    @Inject lateinit var volumeKeyPreferencesStore: VolumeKeyPreferencesStore

    private lateinit var volumeNavEnabled: StateFlow<Boolean>
    private lateinit var invertVolumeKeys: StateFlow<Boolean>

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
        enableEdgeToEdge()
        setContent {
            RiffleTheme {
                MainScreen()
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
