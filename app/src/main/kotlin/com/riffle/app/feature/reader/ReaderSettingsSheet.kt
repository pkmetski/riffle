package com.riffle.app.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.FormattingPreferences

/**
 * In-reader settings host. A fixed-height bottom sheet (does not resize when switching tabs;
 * leaves the page visible behind it to preview changes) with Formatting / Display / Behavior tabs,
 * each rendering the shared section composable. Hosts the reader-only "Reset to global defaults"
 * footer. Opened from the reader's "Aa" toolbar button.
 */
@Composable
fun ReaderSettingsSheet(
    prefs: FormattingPreferences,
    hasBookOverrides: Boolean,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    volumeKeyNavigationEnabled: Boolean,
    onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit,
    invertVolumeKeys: Boolean,
    onInvertVolumeKeysChange: (Boolean) -> Unit,
) {
    val tabs = listOf("Formatting", "Display", "Behavior")
    var selectedTab by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Tap-catcher above the sheet dismisses; reader pane stays visible to preview changes.
        Box(
            modifier = Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 1.dp,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    when (selectedTab) {
                        0 -> FormattingSection(prefs, onPrefsChange)
                        1 -> DisplaySection(prefs, onPrefsChange, scheduleEditable = false)
                        else -> BehaviorSection(
                            keepScreenOn, onKeepScreenOnChange,
                            volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange,
                            invertVolumeKeys, onInvertVolumeKeysChange,
                        )
                    }
                }
                HorizontalDivider()
                TextButton(
                    onClick = onReset,
                    enabled = hasBookOverrides,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 4.dp)
                        .navigationBarsPadding(),
                ) {
                    Text("Reset to global defaults")
                }
            }
        }
    }
}
