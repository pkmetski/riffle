package com.riffle.shared.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AutoReaderThemeMode
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.core.models.HighlightColor
import com.riffle.core.models.ServerType
import com.riffle.feature.settings.AnnotationSyncSubtitle
import com.riffle.feature.settings.AppUpdateUiState
import com.riffle.feature.settings.SettingsViewModel
import com.riffle.feature.source.ui.SourceIcon
import com.riffle.shared.source.SourceOnboardingHost
import org.koin.compose.koinInject

private enum class SettingsPanel {
    None,

    // Source onboarding
    AddSource,

    // Reading panels
    Formatting,
    Display,
    AutoScroll,
    Cadence,

    // Listening
    Listening,

    // Comics
    ComicDisplay,
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel = koinInject<SettingsViewModel>()
    var activePanel by rememberSaveable { mutableStateOf(SettingsPanel.None) }

    when (activePanel) {
        SettingsPanel.AddSource -> {
            SourceOnboardingHost(
                onFinished = { activePanel = SettingsPanel.None },
                onCancelled = { activePanel = SettingsPanel.None },
            )
        }
        SettingsPanel.Formatting -> {
            val prefs by viewModel.globalFormattingPreferences.collectAsState()
            PanelScaffold("Formatting", onDismiss = { activePanel = SettingsPanel.None }) {
                FormattingPanelContent(prefs, onPrefsChange = { viewModel.updateGlobalFormatting(it) })
            }
        }
        SettingsPanel.Display -> {
            val prefs by viewModel.globalFormattingPreferences.collectAsState()
            PanelScaffold("Display", onDismiss = { activePanel = SettingsPanel.None }) {
                DisplayPanelContent(prefs, onPrefsChange = { viewModel.updateGlobalFormatting(it) })
            }
        }
        SettingsPanel.AutoScroll -> {
            val prefs by viewModel.globalFormattingPreferences.collectAsState()
            PanelScaffold("Auto-scroll", onDismiss = { activePanel = SettingsPanel.None }) {
                AutoScrollPanelContent(prefs, onPrefsChange = { viewModel.updateGlobalFormatting(it) })
            }
        }
        SettingsPanel.Cadence -> {
            val prefs by viewModel.globalFormattingPreferences.collectAsState()
            PanelScaffold("Cadence", onDismiss = { activePanel = SettingsPanel.None }) {
                CadencePanelContent(prefs, onPrefsChange = { viewModel.updateGlobalFormatting(it) })
            }
        }
        SettingsPanel.Listening -> {
            val speed by viewModel.defaultPlaybackSpeed.collectAsState()
            val skip by viewModel.skipIntervalSeconds.collectAsState()
            val rewind by viewModel.rewindIntervalSeconds.collectAsState()
            val rewindOnResume by viewModel.rewindOnResumeSeconds.collectAsState()
            PanelScaffold("Listening", onDismiss = { activePanel = SettingsPanel.None }) {
                ListeningPanelContent(
                    defaultPlaybackSpeed = speed,
                    skipIntervalSeconds = skip,
                    rewindIntervalSeconds = rewind,
                    rewindOnResumeSeconds = rewindOnResume,
                    onSpeedChange = { viewModel.setDefaultPlaybackSpeed(it) },
                    onSkipChange = { viewModel.setSkipIntervalSeconds(it) },
                    onRewindChange = { viewModel.setRewindIntervalSeconds(it) },
                    onRewindOnResumeChange = { viewModel.setRewindOnResumeSeconds(it) },
                )
            }
        }
        SettingsPanel.ComicDisplay -> {
            val prefs by viewModel.globalComicFormatting.collectAsState()
            PanelScaffold("Comics Display", onDismiss = { activePanel = SettingsPanel.None }) {
                ComicDisplayPanelContent(prefs, onPrefsChange = { viewModel.updateGlobalComicFormatting(it) })
            }
        }
        SettingsPanel.None -> {
            MainSettingsContent(
                viewModel = viewModel,
                onBack = onBack,
                onOpenPanel = { activePanel = it },
                onAddSource = { activePanel = SettingsPanel.AddSource },
            )
        }
    }
}

@Composable
private fun MainSettingsContent(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenPanel: (SettingsPanel) -> Unit,
    onAddSource: () -> Unit,
) {
    val appTheme by viewModel.appTheme.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val localFilesSource by viewModel.localFilesSource.collectAsState()
    val libraryUiItemsBySource by viewModel.libraryUiItemsBySource.collectAsState()
    val globalFormatting by viewModel.globalFormattingPreferences.collectAsState()
    val globalComicFormatting by viewModel.globalComicFormatting.collectAsState()
    val speed by viewModel.defaultPlaybackSpeed.collectAsState()
    val skip by viewModel.skipIntervalSeconds.collectAsState()
    val rewind by viewModel.rewindIntervalSeconds.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val volumeKeyNavEnabled by viewModel.volumeKeyNavigationEnabled.collectAsState()
    val invertVolumeKeys by viewModel.invertVolumeKeys.collectAsState()
    val annotationSyncRow by viewModel.annotationSyncRow.collectAsState()
    val readaloudSummaries by viewModel.readaloudSummaries.collectAsState()
    val serverVersions by viewModel.serverVersions.collectAsState()
    val developerModeEnabled by viewModel.developerModeEnabled.collectAsState()
    val crashReports by viewModel.crashReports.collectAsState()
    val appUpdateState by viewModel.appUpdateState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Back header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onBack() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            BasicText("← Libraries", style = TextStyle(fontSize = 15.sp, color = Color(0xFF1565C0)))
        }

        BasicText(
            text = "Settings",
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )

        // ── Sources ───────────────────────────────────────────────────────────────────────
        SectionHeader("Sources")
        val nonLocalServers = servers.filter { it.id != localFilesSource?.id }
        if (nonLocalServers.isEmpty()) {
            SettingsRow("No sources configured")
        } else {
            nonLocalServers.forEach { source ->
                val libraryItems = libraryUiItemsBySource[source.id] ?: emptyList()
                SettingsRow(
                    label = source.serverType.label,
                    subtitle = source.url.authority(),
                    trailing = "Remove",
                    onTrailingClick = { viewModel.removeServer(source.id) },
                    leading = { SourceIcon(source = source, size = 28.dp) },
                )
                libraryItems.forEach { item ->
                    LibraryVisibilityRow(
                        name = item.library.name,
                        visible = item.isVisible,
                        switchEnabled = item.switchEnabled,
                        onToggle = { viewModel.setLibraryVisible(source.id, item.library.id, it) },
                    )
                }
            }
        }
        SettingsRow(label = "Add source", trailing = "Add", onTrailingClick = onAddSource)

        // ── Appearance ───────────────────────────────────────────────────────────────────
        SectionHeader("Appearance")
        SettingsRow(label = "App Theme", subtitle = appTheme.label())
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 8.dp)) {
            AppTheme.entries.forEach { theme ->
                BasicText(
                    text = theme.label(),
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = if (appTheme == theme) Color(0xFF1565C0) else Color.DarkGray,
                    ),
                    modifier = Modifier
                        .clickable { viewModel.setAppTheme(theme) }
                        .padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
                )
            }
        }

        // ── Reading ───────────────────────────────────────────────────────────────────────
        SectionHeader("Reading")
        SettingsDrillInRow("Formatting", formattingSummary(globalFormatting)) { onOpenPanel(SettingsPanel.Formatting) }
        SettingsDrillInRow("Display", displaySummary(globalFormatting)) { onOpenPanel(SettingsPanel.Display) }
        SettingsDrillInRow("Auto-scroll", autoScrollSummary(globalFormatting)) { onOpenPanel(SettingsPanel.AutoScroll) }
        if (globalFormatting.cadencePlatformSupported) {
            SettingsDrillInRow("Cadence", cadenceSummary(globalFormatting)) { onOpenPanel(SettingsPanel.Cadence) }
        }

        // ── Listening ─────────────────────────────────────────────────────────────────────
        SectionHeader("Listening")
        SettingsDrillInRow(
            "Preferences",
            "Speed ${formatSpeed(speed)}× · Skip ${skip}s · Rewind ${rewind}s",
        ) { onOpenPanel(SettingsPanel.Listening) }

        // ── Comics ────────────────────────────────────────────────────────────────────────
        SectionHeader("Comics")
        SettingsDrillInRow("Display", comicDisplaySummary(globalComicFormatting)) { onOpenPanel(SettingsPanel.ComicDisplay) }

        // ── Readaloud ─────────────────────────────────────────────────────────────────────
        val storytellerServers = servers.filter { it.serverType == ServerType.STORYTELLER_SERVICE }
        if (storytellerServers.isNotEmpty()) {
            SectionHeader("Readaloud")
            storytellerServers.forEach { st ->
                val summary = readaloudSummaries[st.id]
                SettingsRow(
                    label = st.serverType.label,
                    subtitle = if (summary != null) {
                        "${summary.matchedCount} matched · ${summary.unmatchedCount} unmatched"
                    } else {
                        st.url.authority()
                    },
                )
            }
        }

        // ── Annotations Sync ──────────────────────────────────────────────────────────────
        SectionHeader("Annotations Sync")
        SettingsRow(
            label = annotationSyncRow.headline,
            subtitle = annotationSyncRow.sub.label(),
        )

        // ── Behavior ──────────────────────────────────────────────────────────────────────
        SectionHeader("Behavior")
        ToggleSettingsRow(
            label = "Keep screen on while reading",
            checked = keepScreenOn,
            onCheckedChange = { viewModel.setKeepScreenOn(it) },
        )
        ToggleSettingsRow(
            label = "Volume key navigation",
            checked = volumeKeyNavEnabled,
            onCheckedChange = { viewModel.setVolumeKeyNavigationEnabled(it) },
        )
        if (volumeKeyNavEnabled) {
            ToggleSettingsRow(
                label = "Invert volume keys",
                checked = invertVolumeKeys,
                onCheckedChange = { viewModel.setInvertVolumeKeys(it) },
                indent = true,
            )
        }

        // ── App Version ───────────────────────────────────────────────────────────────────
        SectionHeader("App Version")
        val updateLabel = when (appUpdateState) {
            is AppUpdateUiState.Idle -> "Check for update"
            is AppUpdateUiState.Checking -> "Checking…"
            is AppUpdateUiState.UpToDate -> "Up to date"
            is AppUpdateUiState.UpdateAvailable -> "Update available"
            is AppUpdateUiState.Downloading -> "Downloading…"
            is AppUpdateUiState.Installing -> "Installing…"
            is AppUpdateUiState.Failed -> "Check failed"
        }
        SettingsRow(
            label = "Version",
            subtitle = viewModel.installedVersionName,
            trailing = updateLabel,
            onTrailingClick = { viewModel.checkForUpdate() },
        )

        // ── Diagnostics ───────────────────────────────────────────────────────────────────
        SectionHeader("Diagnostics")
        SettingsRow(
            label = "Crash Reports",
            subtitle = if (crashReports.isEmpty()) "No crashes recorded" else "${crashReports.size} report(s)",
            trailing = if (crashReports.isNotEmpty()) "Clear" else null,
            onTrailingClick = { viewModel.clearCrashReports() },
        )

        // ── Developer Options (unlocked by tapping version 7 times) ───────────────────────
        if (developerModeEnabled) {
            SectionHeader("Developer Options")
            SettingsRow(label = "GitHub PAT", subtitle = "Tap to edit")
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ── Panel scaffold ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PanelScaffold(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFF5F5F5))
                .clickable { onDismiss() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            BasicText("← Back", style = TextStyle(fontSize = 15.sp, color = Color(0xFF1565C0)))
        }
        BasicText(
            text = title,
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Panel content ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun FormattingPanelContent(prefs: FormattingPreferences, onPrefsChange: (FormattingPreferences) -> Unit) {
    PanelSection("Font Size")
    StepperRow(
        label = "${(prefs.fontSize * 100).toInt()}%",
        onDecrement = { onPrefsChange(prefs.copy(fontSize = (prefs.fontSize - 0.1f).coerceAtLeast(0.5f))) },
        onIncrement = { onPrefsChange(prefs.copy(fontSize = (prefs.fontSize + 0.1f).coerceAtMost(3.0f))) },
    )
    PanelSection("Font Family")
    ChipRow(
        options = ReaderFontFamily.entries.map { it.label() },
        selected = prefs.fontFamily.label(),
        onSelect = { label ->
            ReaderFontFamily.entries.firstOrNull { it.label() == label }
                ?.let { onPrefsChange(prefs.copy(fontFamily = it)) }
        },
    )
    PanelSection("Line Spacing")
    StepperRow(
        label = prefs.lineSpacing.to1dp(),
        onDecrement = { onPrefsChange(prefs.copy(lineSpacing = (prefs.lineSpacing - 0.1f).coerceAtLeast(1.0f))) },
        onIncrement = { onPrefsChange(prefs.copy(lineSpacing = (prefs.lineSpacing + 0.1f).coerceAtMost(3.0f))) },
    )
    PanelSection("Margins")
    StepperRow(
        label = "${(prefs.margins * 100).toInt()}%",
        onDecrement = { onPrefsChange(prefs.copy(margins = (prefs.margins - 0.1f).coerceAtLeast(0.0f))) },
        onIncrement = { onPrefsChange(prefs.copy(margins = (prefs.margins + 0.1f).coerceAtMost(2.0f))) },
    )
    PanelSection("Options")
    PanelToggleRow("Justify text", prefs.justifyText) { onPrefsChange(prefs.copy(justifyText = it)) }
}

@Composable
private fun DisplayPanelContent(prefs: FormattingPreferences, onPrefsChange: (FormattingPreferences) -> Unit) {
    PanelSection("Reading Mode")
    ChipRow(
        options = listOf("Paginated", "Scroll", "Continuous"),
        selected = prefs.orientation.displayLabel(),
        onSelect = { label ->
            val ori = when (label) {
                "Paginated" -> ReaderOrientation.Horizontal
                "Scroll" -> ReaderOrientation.Vertical
                "Continuous" -> ReaderOrientation.Continuous
                else -> prefs.orientation
            }
            onPrefsChange(prefs.copy(orientation = ori))
        },
    )
    PanelSection("Landscape")
    PanelToggleRow("Force paginated in landscape", prefs.forcePaginatedInLandscape) {
        onPrefsChange(prefs.copy(forcePaginatedInLandscape = it))
    }
    PanelToggleRow("Double-page spread in landscape", prefs.doublePageSpread) {
        onPrefsChange(prefs.copy(doublePageSpread = it))
    }
    PanelSection("Reader Theme")
    ChipRow(
        options = listOf("Light", "Dark", "Dim", "Sepia", "Auto"),
        selected = prefs.theme.displayLabel(),
        onSelect = { label ->
            val theme = when (label) {
                "Light" -> ReaderTheme.Light
                "Dark" -> ReaderTheme.Dark
                "Dim" -> ReaderTheme.DarkDim
                "Sepia" -> ReaderTheme.Sepia
                "Auto" -> ReaderTheme.Auto
                else -> prefs.theme
            }
            onPrefsChange(prefs.copy(theme = theme))
        },
    )
    PanelSection("Auto Theme Mode")
    ChipRow(
        options = listOf("Schedule", "Follow app theme"),
        selected = if (prefs.autoReaderThemeMode == AutoReaderThemeMode.Schedule) "Schedule" else "Follow app theme",
        onSelect = { label ->
            val mode = if (label == "Schedule") AutoReaderThemeMode.Schedule else AutoReaderThemeMode.AppTheme
            onPrefsChange(prefs.copy(autoReaderThemeMode = mode))
        },
    )
    PanelSection("On-Screen Info")
    PanelToggleRow("Chapter map", prefs.showChapterMap) { onPrefsChange(prefs.copy(showChapterMap = it)) }
    PanelToggleRow("Colored chapter map", prefs.coloredChapterMap) { onPrefsChange(prefs.copy(coloredChapterMap = it)) }
    PanelToggleRow("Current chapter label", prefs.showCurrentChapterLabel) { onPrefsChange(prefs.copy(showCurrentChapterLabel = it)) }
    PanelToggleRow("Reading progress labels", prefs.showReadingProgressLabels) { onPrefsChange(prefs.copy(showReadingProgressLabels = it)) }
    PanelToggleRow("Time remaining", prefs.showReadingTimeEstimate) { onPrefsChange(prefs.copy(showReadingTimeEstimate = it)) }
}

@Composable
private fun AutoScrollPanelContent(prefs: FormattingPreferences, onPrefsChange: (FormattingPreferences) -> Unit) {
    PanelSection("Auto-scroll")
    PanelToggleRow("Show auto-scroll toggle in reader", prefs.showAutoScroll) {
        onPrefsChange(prefs.copy(showAutoScroll = it))
    }
    PanelSection("Speed (WPM)")
    StepperRow(
        label = "${prefs.autoScrollWpm} WPM",
        onDecrement = { onPrefsChange(prefs.copy(autoScrollWpm = (prefs.autoScrollWpm - 10).coerceAtLeast(50))) },
        onIncrement = { onPrefsChange(prefs.copy(autoScrollWpm = (prefs.autoScrollWpm + 10).coerceAtMost(1000))) },
    )
}

@Composable
private fun CadencePanelContent(prefs: FormattingPreferences, onPrefsChange: (FormattingPreferences) -> Unit) {
    if (!prefs.cadencePlatformSupported) {
        PanelSection("Cadence")
        BasicText(
            text = "Cadence is not available on this device. A WebView update may enable it.",
            style = TextStyle(fontSize = 14.sp, color = Color.Gray),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    PanelSection("Cadence")
    PanelToggleRow("Show cadence toggle in reader", prefs.showCadence) {
        onPrefsChange(prefs.copy(showCadence = it))
    }
    PanelSection("Speed (WPM)")
    StepperRow(
        label = "${prefs.cadenceWpm} WPM",
        onDecrement = { onPrefsChange(prefs.copy(cadenceWpm = (prefs.cadenceWpm - 10).coerceAtLeast(50))) },
        onIncrement = { onPrefsChange(prefs.copy(cadenceWpm = (prefs.cadenceWpm + 10).coerceAtMost(1000))) },
    )
    PanelSection("Highlight Color")
    ChipRow(
        options = HighlightColor.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
        selected = prefs.cadenceHighlightColor.name.lowercase().replaceFirstChar { c -> c.uppercase() },
        onSelect = { label ->
            HighlightColor.entries.firstOrNull { it.name.equals(label, ignoreCase = true) }
                ?.let { onPrefsChange(prefs.copy(cadenceHighlightColor = it)) }
        },
    )
}

@Composable
private fun ListeningPanelContent(
    defaultPlaybackSpeed: Float,
    skipIntervalSeconds: Int,
    rewindIntervalSeconds: Int,
    rewindOnResumeSeconds: Int,
    onSpeedChange: (Float) -> Unit,
    onSkipChange: (Int) -> Unit,
    onRewindChange: (Int) -> Unit,
    onRewindOnResumeChange: (Int) -> Unit,
) {
    PanelSection("Playback Speed")
    StepperRow(
        label = "${formatSpeed(defaultPlaybackSpeed)}×",
        onDecrement = { onSpeedChange((defaultPlaybackSpeed - 0.1f).coerceAtLeast(0.5f).roundToStep()) },
        onIncrement = { onSpeedChange((defaultPlaybackSpeed + 0.1f).coerceAtMost(4.0f).roundToStep()) },
    )
    PanelSection("Skip Forward (seconds)")
    StepperRow(
        label = "${skipIntervalSeconds}s",
        onDecrement = { onSkipChange((skipIntervalSeconds - 5).coerceAtLeast(5)) },
        onIncrement = { onSkipChange((skipIntervalSeconds + 5).coerceAtMost(120)) },
    )
    PanelSection("Rewind (seconds)")
    StepperRow(
        label = "${rewindIntervalSeconds}s",
        onDecrement = { onRewindChange((rewindIntervalSeconds - 5).coerceAtLeast(5)) },
        onIncrement = { onRewindChange((rewindIntervalSeconds + 5).coerceAtMost(120)) },
    )
    PanelSection("Rewind on Resume (seconds)")
    StepperRow(
        label = "${rewindOnResumeSeconds}s",
        onDecrement = { onRewindOnResumeChange((rewindOnResumeSeconds - 5).coerceAtLeast(0)) },
        onIncrement = { onRewindOnResumeChange((rewindOnResumeSeconds + 5).coerceAtMost(120)) },
    )
}

@Composable
private fun ComicDisplayPanelContent(
    prefs: ComicFormattingPreferences,
    onPrefsChange: (ComicFormattingPreferences) -> Unit,
) {
    PanelSection("Background Theme")
    ChipRow(
        options = listOf("Light", "Dark", "Sepia"),
        selected = prefs.backgroundTheme.displayLabel(),
        onSelect = { label ->
            val theme = when (label) {
                "Light" -> ReaderTheme.Light
                "Dark" -> ReaderTheme.Dark
                "Sepia" -> ReaderTheme.Sepia
                else -> prefs.backgroundTheme
            }
            onPrefsChange(prefs.copy(backgroundTheme = theme))
        },
    )
    PanelSection("Panel View")
    PanelToggleRow("Enable panel view", prefs.panelViewOn) { onPrefsChange(prefs.copy(panelViewOn = it)) }
    if (prefs.panelViewOn) {
        ChipRow(
            options = listOf("Split", "Smart split", "No split"),
            selected = when (prefs.panelOverflow) {
                PanelOverflowBehavior.SPLIT -> "Split"
                PanelOverflowBehavior.SMART_SPLIT -> "Smart split"
                PanelOverflowBehavior.OFF -> "No split"
            },
            onSelect = { label ->
                val overflow = when (label) {
                    "Split" -> PanelOverflowBehavior.SPLIT
                    "Smart split" -> PanelOverflowBehavior.SMART_SPLIT
                    else -> PanelOverflowBehavior.OFF
                }
                onPrefsChange(prefs.copy(panelOverflow = overflow))
            },
        )
    }
    PanelSection("HUD")
    PanelToggleRow("Reading progress", prefs.showChapterMap) { onPrefsChange(prefs.copy(showChapterMap = it)) }
    PanelToggleRow("Page numbers", prefs.showPageProgress) { onPrefsChange(prefs.copy(showPageProgress = it)) }
}

// ── Primitive row components ──────────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    BasicText(
        text = title,
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0)),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PanelSection(title: String) {
    BasicText(
        text = title,
        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1565C0)),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(
    label: String,
    subtitle: String? = null,
    trailing: String? = null,
    onTrailingClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    indent: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 32.dp else 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            BasicText(label, style = TextStyle(fontSize = 14.sp))
            if (subtitle != null) {
                BasicText(subtitle, style = TextStyle(fontSize = 12.sp, color = Color.Gray))
            }
        }
        if (trailing != null) {
            BasicText(
                trailing,
                style = TextStyle(fontSize = 13.sp, color = Color(0xFF1565C0)),
                modifier = Modifier
                    .testTag("settings-trailing-$trailing")
                    .clickable { onTrailingClick?.invoke() }
                    .padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun SettingsDrillInRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = TextStyle(fontSize = 14.sp))
            BasicText(summary, style = TextStyle(fontSize = 12.sp, color = Color.Gray))
        }
        BasicText("›", style = TextStyle(fontSize = 18.sp, color = Color.Gray))
    }
}

@Composable
private fun ToggleSettingsRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    indent: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(start = if (indent) 32.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = TextStyle(fontSize = 14.sp), modifier = Modifier.weight(1f))
        BasicText(
            text = if (checked) "ON" else "OFF",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (checked) Color(0xFF1565C0) else Color.Gray,
            ),
        )
    }
}

@Composable
private fun LibraryVisibilityRow(
    name: String,
    visible: Boolean,
    switchEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = switchEnabled) { onToggle(!visible) }
            .padding(start = 32.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            name,
            style = TextStyle(
                fontSize = 13.sp,
                color = if (switchEnabled) Color.DarkGray else Color.Gray,
            ),
            modifier = Modifier.weight(1f),
        )
        BasicText(
            text = if (visible) "Visible" else "Hidden",
            style = TextStyle(
                fontSize = 12.sp,
                color = if (visible) Color(0xFF1565C0) else Color.Gray,
            ),
        )
    }
}

@Composable
private fun PanelToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(label, style = TextStyle(fontSize = 14.sp), modifier = Modifier.weight(1f))
        BasicText(
            text = if (checked) "ON" else "OFF",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (checked) Color(0xFF1565C0) else Color.Gray,
            ),
        )
    }
}

@Composable
private fun StepperRow(label: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            "−",
            style = TextStyle(fontSize = 20.sp, color = Color(0xFF1565C0)),
            modifier = Modifier
                .clickable { onDecrement() }
                .padding(end = 16.dp),
        )
        BasicText(label, style = TextStyle(fontSize = 14.sp), modifier = Modifier.weight(1f))
        BasicText(
            "+",
            style = TextStyle(fontSize = 20.sp, color = Color(0xFF1565C0)),
            modifier = Modifier
                .clickable { onIncrement() }
                .padding(start = 16.dp),
        )
    }
}

@Composable
private fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .padding(end = 8.dp, top = 4.dp, bottom = 4.dp)
                    .background(
                        if (isSelected) Color(0xFF1565C0) else Color(0xFFEEEEEE),
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                BasicText(
                    option,
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = if (isSelected) Color.White else Color.DarkGray,
                    ),
                )
            }
        }
    }
}

// ── Summary helpers ───────────────────────────────────────────────────────────────────────────────

private fun AppTheme.label(): String = when (this) {
    AppTheme.Light -> "Light"
    AppTheme.Dark -> "Dark"
    AppTheme.System -> "System"
}

private fun ReaderFontFamily.label(): String = when (this) {
    ReaderFontFamily.Original -> "Original"
    ReaderFontFamily.Serif -> "Serif"
    ReaderFontFamily.SansSerif -> "Sans-serif"
    ReaderFontFamily.Monospace -> "Monospace"
    ReaderFontFamily.Literata -> "Literata"
    ReaderFontFamily.Merriweather -> "Merriweather"
    ReaderFontFamily.OpenDyslexic -> "OpenDyslexic"
}

private fun ReaderOrientation.displayLabel(): String = when (this) {
    ReaderOrientation.Horizontal -> "Paginated"
    ReaderOrientation.Vertical -> "Scroll"
    ReaderOrientation.Continuous -> "Continuous"
}

private fun ReaderTheme.displayLabel(): String = when (this) {
    ReaderTheme.Light -> "Light"
    ReaderTheme.Dark -> "Dark"
    ReaderTheme.DarkDim -> "Dim"
    ReaderTheme.Sepia -> "Sepia"
    ReaderTheme.Auto -> "Auto"
}

private fun formattingSummary(prefs: FormattingPreferences): String =
    "${prefs.fontFamily.label()} · ${(prefs.fontSize * 100).toInt()}% · " +
        "Spacing ${prefs.lineSpacing.to1dp()}"

private fun displaySummary(prefs: FormattingPreferences): String =
    "${prefs.orientation.displayLabel()} · ${prefs.theme.displayLabel()}"

private fun autoScrollSummary(prefs: FormattingPreferences): String =
    if (prefs.showAutoScroll) "${prefs.autoScrollWpm} WPM" else "Off"

private fun cadenceSummary(prefs: FormattingPreferences): String =
    if (prefs.showCadence) "${prefs.cadenceWpm} WPM" else "Off"

private fun comicDisplaySummary(prefs: ComicFormattingPreferences): String = buildString {
    append(prefs.backgroundTheme.displayLabel())
    append(" · ")
    append(
        if (prefs.panelViewOn) {
            when (prefs.panelOverflow) {
                PanelOverflowBehavior.SPLIT -> "Panel view · Split"
                PanelOverflowBehavior.SMART_SPLIT -> "Panel view · Smart split"
                PanelOverflowBehavior.OFF -> "Panel view · No split"
            }
        } else {
            "Panel view off"
        },
    )
}

private fun formatSpeed(speed: Float): String {
    val rounded = (speed * 10).toInt() / 10.0f
    return if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()}" else rounded.to1dp()
}

private fun Float.roundToStep(): Float = (this * 10).toInt() / 10.0f

/** One-decimal-place float string without java.lang.String.format (not available in commonMain). */
private fun Float.to1dp(): String {
    val tenths = (this * 10).toInt()
    return "${tenths / 10}.${tenths % 10}"
}

private fun AnnotationSyncSubtitle.label(): String = when (this) {
    is AnnotationSyncSubtitle.NotConfigured -> "Not configured — local only"
    is AnnotationSyncSubtitle.WaitingForFirstSync -> "Waiting for first sync…"
    is AnnotationSyncSubtitle.AuthFailed -> "Authentication failed"
    is AnnotationSyncSubtitle.TlsError -> "TLS/certificate error"
    is AnnotationSyncSubtitle.HttpError -> "Server error (HTTP $code)"
    is AnnotationSyncSubtitle.SyncFailed -> "Sync failed"
    is AnnotationSyncSubtitle.BooksPendingOffline -> "$count book(s) pending · Offline"
    is AnnotationSyncSubtitle.Offline -> "Offline"
    is AnnotationSyncSubtitle.Synced -> if (identity != null) "Synced · $identity" else "Synced"
}
