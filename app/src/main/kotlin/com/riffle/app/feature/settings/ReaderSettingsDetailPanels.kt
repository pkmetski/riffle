package com.riffle.app.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.reader.BehaviorSection
import com.riffle.app.feature.reader.DisplaySection
import com.riffle.app.feature.reader.FormattingSection
import com.riffle.app.feature.reader.autoscroll.AutoScrollToggleIcon
import com.riffle.app.feature.reader.cadence.CadenceHeroIcon
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.autoscroll.AutoScrollSpeed

/**
 * Global Settings drill-in screens for the three reader-settings categories. Each is a full-screen
 * panel (mirroring [ListeningPreferencesPanel]) wrapping the shared section composable. The Display
 * panel passes `scheduleEditable = true` so the Auto day/night schedule editor is shown here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    Surface(modifier = Modifier.fillMaxSize().statusBarsPadding(), tonalElevation = 1.dp) {
        Column {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
            ) { content() }
        }
    }
}

@Composable
fun FormattingSettingsPanel(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Formatting", onDismiss) { FormattingSection(prefs, onPrefsChange) }

@Composable
fun DisplaySettingsPanel(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Display", onDismiss) { DisplaySection(prefs, onPrefsChange, scheduleEditable = true) }

/**
 * Auto-Scroll drill-in — the reader's "creep the page upward at a set pace" feature. Promoted out of
 * the reader's `Behavior` panel per issue #403 into its own sibling under `Settings → Reading`, so
 * `Behavior` stays scoped to *device* behaviour (wake lock, hardware volume keys) while Auto-Scroll
 * — a *reading feature* with on/off + speed — gets its own home alongside the Cadence drill-in.
 *
 * Renders the 40×40 hero glyph at the top of the About blurb so the icon the user sees in the
 * reader top bar and the row-leading icon in this Settings list all read as the same feature.
 */
@Composable
fun AutoScrollSettingsPanel(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Auto-Scroll", onDismiss) {
    // Hero icon — same shape as the reader-top-bar toggle, at 40 dp. Reuses AutoScrollToggleIcon's
    // idle glyph via a hidden IconButton wrapper wouldn't have a hero variant — draw it directly.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        // AutoScrollToggleIcon is an IconButton (touch-target 48dp) but we reuse it for
        // visual consistency — the hero position is decorative; disabled onClick.
        AutoScrollToggleIcon(isRunning = false, onClick = {})
    }
    Text(
        text = "Auto-Scroll creeps the page upward at a set pace so you can read hands-free. " +
            "Available in scrolling reading modes. Start and stop from the reader top bar; " +
            "nudge speed with the volume keys while running.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    Text(
        text = "This icon appears in the reader top bar to start and stop Auto-Scroll.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 20.dp),
    )
    ListItem(
        headlineContent = { Text("Show Auto-Scroll") },
        supportingContent = { Text("Adds the toggle to the reader top bar (Vertical & Continuous only)") },
        trailingContent = {
            Switch(
                checked = prefs.showAutoScroll,
                onCheckedChange = { onPrefsChange(prefs.copy(showAutoScroll = it)) },
            )
        },
    )
    WpmSliderRow(
        label = "Default speed",
        helper = "Per-book override in the reader's Formatting panel. Adjust live with the volume keys while running.",
        wpm = prefs.autoScrollWpm,
        onWpmChange = { onPrefsChange(prefs.copy(autoScrollWpm = it)) },
    )
}

/**
 * Cadence drill-in — the sentence-highlight hands-free reading feature. See issue #403 / ADR 0040.
 *
 * [platformSupported] is the WebView `Intl.Segmenter` gate. When false, the whole drill-in body
 * shows a "not supported on this WebView" note instead of the toggles — same posture as
 * Storyteller-not-configured. The Reading-list row that opens this panel should also hide itself
 * globally in that case; this fallback body is a defence in depth in case the user reaches the
 * panel some other way (e.g. quick-settings deep link).
 */
@Composable
fun CadenceSettingsPanel(
    prefs: FormattingPreferences,
    onPrefsChange: (FormattingPreferences) -> Unit,
    platformSupported: Boolean = true,
    onDismiss: () -> Unit,
) = DetailScaffold("Cadence", onDismiss) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        CadenceHeroIcon()
    }
    Text(
        text = "Cadence highlights one sentence at a time and advances on its own at a set " +
            "pace, so you can read hands-free. Start and stop from the reader top bar; " +
            "nudge speed with the volume keys while running.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    Text(
        text = "This icon appears in the reader top bar to start and stop Cadence.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 20.dp),
    )
    if (!platformSupported) {
        Text(
            text = "Cadence isn't available on this device's WebView. Update the Android System " +
                "WebView from the Play Store to enable it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return@DetailScaffold
    }
    ListItem(
        headlineContent = { Text("Show Cadence") },
        supportingContent = { Text("Adds the toggle to the reader top bar (all orientations)") },
        trailingContent = {
            Switch(
                checked = prefs.showCadence,
                onCheckedChange = { onPrefsChange(prefs.copy(showCadence = it)) },
            )
        },
    )
    WpmSliderRow(
        label = "Default speed",
        helper = "Per-book override in the reader's Formatting panel. Adjust live with the volume keys while running.",
        wpm = prefs.cadenceWpm,
        onWpmChange = { onPrefsChange(prefs.copy(cadenceWpm = it)) },
    )
    HighlightColorRow(
        selected = prefs.cadenceHighlightColor,
        onSelectedChange = { onPrefsChange(prefs.copy(cadenceHighlightColor = it)) },
    )
}

/**
 * Shared WPM slider — Cadence and Auto-Scroll use the same 80–600 wpm, step 10, default 250 range
 * per issue #403 (both reuse [AutoScrollSpeed]'s constants). Snapping happens inside
 * [AutoScrollSpeed.of] so the caller can't emit a mid-step value.
 */
@Composable
private fun WpmSliderRow(
    label: String,
    helper: String,
    wpm: Int,
    onWpmChange: (Int) -> Unit,
) {
    Text(
        text = "$label — $wpm wpm",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    Slider(
        value = wpm.toFloat(),
        onValueChange = { onWpmChange(AutoScrollSpeed.of(it.toInt()).wpm) },
        valueRange = AutoScrollSpeed.MIN_WPM.toFloat()..AutoScrollSpeed.MAX_WPM.toFloat(),
        // Steps = (max - min)/step - 1 = (600 - 80)/10 - 1 = 51 discrete stops between endpoints.
        steps = (AutoScrollSpeed.MAX_WPM - AutoScrollSpeed.MIN_WPM) / AutoScrollSpeed.STEP_WPM - 1,
    )
    Text(
        text = helper,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * Colour-swatch picker for Cadence's highlight — mirrors the Readaloud picker in `SettingsScreen`
 * to keep the visual pattern identical (issue #403: "Independent from Readaloud. Same palette.").
 * Selected swatch carries a contrast ring + a check glyph so the pick is unambiguous in the
 * screenshot the QA tester takes.
 */
@Composable
private fun HighlightColorRow(
    selected: HighlightColor,
    onSelectedChange: (HighlightColor) -> Unit,
) {
    ListItem(
        headlineContent = { Text("Highlight color") },
        supportingContent = { Text("Independent from Readaloud. Same palette.") },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HighlightColor.entries.forEach { color ->
                    val isSelected = selected == color
                    val swatchColor = Color(color.argb.toLong() and 0xFFFFFFFFL)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { onSelectedChange(color) }
                            .then(
                                if (isSelected)
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                else Modifier,
                            )
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .semantics {
                                contentDescription = color.name.lowercase()
                                    .replaceFirstChar { it.uppercase() } +
                                    " highlight" + if (isSelected) ", selected" else ""
                            },
                    ) {
                        if (isSelected) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null)
                        }
                    }
                }
            }
        },
    )
}

@Composable
fun BehaviorSettingsPanel(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    volumeKeyNavigationEnabled: Boolean,
    onVolumeKeyNavigationEnabledChange: (Boolean) -> Unit,
    invertVolumeKeys: Boolean,
    onInvertVolumeKeysChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) = DetailScaffold("Behavior", onDismiss) {
    BehaviorSection(
        keepScreenOn, onKeepScreenOnChange,
        volumeKeyNavigationEnabled, onVolumeKeyNavigationEnabledChange,
        invertVolumeKeys, onInvertVolumeKeysChange,
    )
}
