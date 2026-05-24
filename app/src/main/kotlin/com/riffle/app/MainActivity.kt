package com.riffle.app

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (consumedVolumeKeyCodes.remove(keyCode)) return true
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
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
                VolumeKeyAction.PassThrough -> super.dispatchKeyEvent(event)
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
