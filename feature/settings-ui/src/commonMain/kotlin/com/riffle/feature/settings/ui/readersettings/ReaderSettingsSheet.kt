package com.riffle.feature.settings.ui.readersettings

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.FormattingPreferences
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.ui_display
import com.riffle.feature.settings.ui.generated.resources.ui_formatting
import com.riffle.feature.settings.ui.generated.resources.ui_reset_to_global_defaults
import com.riffle.feature.settings.ui.readersettings.formatting.RenderCapabilities
import org.jetbrains.compose.resources.stringResource

/**
 * In-reader settings sheet for both Android and iOS. Fixed-height bottom surface (does not
 * resize when switching tabs so the page remains visible behind it to preview changes) with
 * Formatting / Display tabs. Hosts the per-book "Reset to global defaults" footer.
 *
 * [capabilities] gates rows that don't apply to the current renderer (e.g. font-family and
 * reading-mode switching are not available in the PDF renderer).
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
fun ReaderSettingsSheet(
    prefs: FormattingPreferences,
    capabilities: RenderCapabilities,
    hasBookOverrides: Boolean,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val formattingLabel = stringResource(Res.string.ui_formatting)
    val displayLabel = stringResource(Res.string.ui_display)
    val tabs = remember(formattingLabel, displayLabel) { listOf(formattingLabel, displayLabel) }
    var selectedTab by remember { mutableIntStateOf(0) }

    BackHandler(onBack = onDismiss)

    Box(modifier = Modifier.fillMaxSize()) {
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
                SecondaryTabRow(selectedTabIndex = selectedTab) {
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
                        0 -> FormattingSection(prefs, onPrefsChange, capabilities)
                        else -> DisplaySection(
                            prefs,
                            onPrefsChange,
                            scheduleEditable = false,
                            capabilities = capabilities,
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
                    Text(stringResource(Res.string.ui_reset_to_global_defaults))
                }
            }
        }
    }
}
