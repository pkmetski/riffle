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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.riffle.core.data.localfiles.FolderPickerInterface
import com.riffle.core.data.localfiles.LocalFilesInstallerInterface
import com.riffle.core.domain.AppTheme
import com.riffle.core.domain.AutoReaderThemeMode
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.comic.ComicBackgroundThemeOptions
import com.riffle.core.domain.comic.ComicFormattingPreferences
import com.riffle.core.domain.comic.asComicBackgroundTheme
import com.riffle.core.models.HighlightColor
import com.riffle.core.models.ServerType
import com.riffle.core.models.Source
import com.riffle.feature.designsystem.SectionHeader
import com.riffle.feature.designsystem.SettingsSectionHeader
import com.riffle.feature.player.PlaybackSpeed
import com.riffle.feature.player.SkipIntervals
import com.riffle.feature.settings.AppUpdateStatus
import com.riffle.feature.settings.PanelOverflowOptions
import com.riffle.feature.settings.ReadaloudMatchSummary
import com.riffle.feature.settings.ReaderSettingsSections
import com.riffle.feature.settings.ReaderSettingsSummaries
import com.riffle.feature.settings.SettingsViewModel
import com.riffle.feature.settings.comicDisplaySummary
import com.riffle.feature.settings.label
import com.riffle.feature.settings.readaloudRowSummary
import com.riffle.feature.source.ui.settings.SourcesSection
import com.riffle.feature.designsystem.TestTags
import com.riffle.shared.source.SourceOnboardingHost
import com.riffle.shared.source.WebdavOnboardingHost
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class SettingsPanel {
    None,

    // Source onboarding
    AddSource,
    ConnectWebdav,

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
        SettingsPanel.ConnectWebdav -> {
            WebdavOnboardingHost(
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
            PanelScaffold(CADENCE_PANEL_TITLE, onDismiss = { activePanel = SettingsPanel.None }) {
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
                onConnectWebdav = { activePanel = SettingsPanel.ConnectWebdav },
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
    onConnectWebdav: () -> Unit,
) {
    val appTheme by viewModel.appTheme.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val localFilesSource by viewModel.localFilesSource.collectAsState()
    val localFilesFolders by viewModel.localFilesFolders.collectAsState()
    val localFilesFolderHealth by viewModel.localFilesFolderHealth.collectAsState()
    val singletonWebSources by viewModel.singletonWebSources.collectAsState()
    val libraryUiItemsBySource by viewModel.libraryUiItemsBySource.collectAsState()
    val globalFormatting by viewModel.globalFormattingPreferences.collectAsState()
    val globalComicFormatting by viewModel.globalComicFormatting.collectAsState()
    val speed by viewModel.defaultPlaybackSpeed.collectAsState()
    val skip by viewModel.skipIntervalSeconds.collectAsState()
    val rewind by viewModel.rewindIntervalSeconds.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val annotationSyncRow by viewModel.annotationSyncRow.collectAsState()
    val readaloudSummaries by viewModel.readaloudSummaries.collectAsState()
    val serverVersions by viewModel.serverVersions.collectAsState()
    val developerModeEnabled by viewModel.developerModeEnabled.collectAsState()
    val crashReports by viewModel.crashReports.collectAsState()
    val appUpdateState by viewModel.appUpdateState.collectAsState()

    // Which source rows are open. A plain snapshot map rather than rememberSaveable: the same
    // shape Android's Settings uses, so both hosts collapse everything on a cold start.
    val expandedSources = remember { mutableStateMapOf<String, Boolean>() }
    // Android re-probes folder permissions on ON_RESUME; iOS has no lifecycle observer here, so
    // the probe runs when the screen enters composition. Without it every folder renders as
    // healthy and the "needs attention" warning can never appear.
    LaunchedEffect(Unit) { viewModel.refreshLocalFilesFolderHealth() }

    val folderPicker = koinInject<FolderPickerInterface>()
    val localFilesInstaller = koinInject<LocalFilesInstallerInterface>()
    val scope = rememberCoroutineScope()

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
        //
        // The list itself is `feature:source-ui`'s shared SourcesSection — the same composable
        // Android renders, so the expand/collapse, the source version in the subtitle, the
        // swipe-to-delete gesture, the singleton web-source rows, the readaloud drill-in and the
        // Local Files folder manager all arrive here rather than being re-written thinner. What
        // used to be here was a flat "type · authority + Remove" row that dropped every one of
        // those, and filtered the Local Files source out entirely — so `localFilesFolders`,
        // `localFilesFolderHealth`, `removeLocalFolder()` and `removeLocalFilesSource()` had no
        // caller on this platform at all.
        SectionHeader("Sources")
        SourcesSection(
            servers = servers,
            localFilesSource = localFilesSource,
            localFilesFolders = localFilesFolders,
            localFilesFolderHealth = localFilesFolderHealth,
            singletonWebSources = singletonWebSources,
            sourceVersions = serverVersions,
            libraryItemsBySource = libraryUiItemsBySource,
            readaloudSummaries = readaloudSummaries,
            expandedSources = expandedSources,
            onNavigateToAddSourcePicker = onAddSource,
            onNavigateToAddLocalFolder = {
                folderPicker.pickFolder { uri ->
                    if (uri != null) scope.launch { runCatching { localFilesInstaller.installFolder(uri) } }
                }
            },
            onOpenReadaloudMatches = { /* Storyteller is a Service (ADR 0024), not a Sources row. */ },
            onRemoveSource = { viewModel.removeServer(it) },
            onRemoveLocalFolder = { viewModel.removeLocalFolder(it) },
            onRemoveLocalFilesSource = { viewModel.removeLocalFilesSource() },
            onSetLibraryVisible = { sourceId, libraryId, visible ->
                viewModel.setLibraryVisible(sourceId, libraryId, visible)
            },
            onReorderLibraries = { sourceId, orderedIds -> viewModel.setLibraryOrder(sourceId, orderedIds) },
        )

        // ── Appearance ───────────────────────────────────────────────────────────────────
        SettingsSectionHeader("Appearance")
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
        SettingsSectionHeader("Reading")
        SettingsDrillInRow("Formatting", ReaderSettingsSummaries.formattingSummary(globalFormatting)) { onOpenPanel(SettingsPanel.Formatting) }
        SettingsDrillInRow("Display", ReaderSettingsSummaries.displaySummary(globalFormatting)) { onOpenPanel(SettingsPanel.Display) }
        SettingsDrillInRow("Auto-scroll", ReaderSettingsSummaries.autoScrollSummary(globalFormatting)) {
            onOpenPanel(SettingsPanel.AutoScroll)
        }
        // Gated on the reader's `Intl.Segmenter` probe, the same gate Android's row uses: the
        // preference is persisted by the reader's feature detect, so Settings knows the answer
        // even when opened with no book open.
        if (globalFormatting.cadencePlatformSupported) {
            SettingsDrillInRow(CADENCE_PANEL_TITLE, ReaderSettingsSummaries.cadenceSummary(globalFormatting)) {
                onOpenPanel(SettingsPanel.Cadence)
            }
        }

        // ── Listening ─────────────────────────────────────────────────────────────────────
        SettingsSectionHeader("Listening")
        SettingsDrillInRow(
            "Preferences",
            listeningSummary(speed, skip, rewind),
        ) { onOpenPanel(SettingsPanel.Listening) }

        // ── Comics ────────────────────────────────────────────────────────────────────────
        SettingsSectionHeader("Comics")
        SettingsDrillInRow("Display", comicDisplaySummary(globalComicFormatting)) { onOpenPanel(SettingsPanel.ComicDisplay) }

        // ── Readaloud ─────────────────────────────────────────────────────────────────────
        val storytellerServers = servers.filter { it.serverType == ServerType.STORYTELLER_SERVICE }
        if (storytellerServers.isNotEmpty()) {
            SettingsSectionHeader("Readaloud")
            storytellerServers.forEach { st ->
                SettingsRow(
                    label = st.serverType.label,
                    subtitle = readaloudSubtitle(st, serverVersions, readaloudSummaries),
                )
            }
        }

        // ── Annotations Sync ──────────────────────────────────────────────────────────────
        SectionHeader("Annotations Sync")
        // Tappable, and it opens the real WebDAV connect form. Before the WebDAV client moved to
        // commonMain this row was a dead readout: the form existed in the shared AddSourceScreen
        // but no iOS code path could construct its backend, and the connection tester it calls
        // was bound to a lambda that returned "unparseable URL" for every input.
        SettingsRow(
            label = annotationSyncRow.headline,
            subtitle = annotationSyncRow.sub.label(),
            trailing = "Configure",
            onTrailingClick = onConnectWebdav,
        )

        // ── Behavior ──────────────────────────────────────────────────────────────────────
        SettingsSectionHeader("Behavior")
        ToggleSettingsRow(
            label = "Keep screen on while reading",
            checked = keepScreenOn,
            onCheckedChange = { viewModel.setKeepScreenOn(it) },
        )
        // No volume-key rows: iOS hands hardware volume presses to the system before any app
        // can consume them (see IosVolumeKeyPreferencesStoreImpl), so both switches were
        // decorative — and gating the invert switch on the parent made the pair look
        // functional. The preference itself still round-trips for cross-device parity; only
        // the iOS UI is gone.

        // ── App Version ───────────────────────────────────────────────────────────────────
        SettingsSectionHeader("App Version")
        SettingsRow(
            label = "Version",
            subtitle = AppUpdateStatus.statusText(appUpdateState, viewModel.installedVersionName),
            trailing = AppUpdateStatus.actionLabel(appUpdateState),
            onTrailingClick = { viewModel.checkForUpdate() },
        )

        // ── Diagnostics ───────────────────────────────────────────────────────────────────
        SettingsSectionHeader("Diagnostics")
        SettingsRow(
            label = "Crash Reports",
            subtitle = if (crashReports.isEmpty()) "No crashes recorded" else "${crashReports.size} report(s)",
            trailing = if (crashReports.isNotEmpty()) "Clear" else null,
            onTrailingClick = { viewModel.clearCrashReports() },
        )

        // ── Developer Options (unlocked by tapping version 7 times) ───────────────────────
        if (developerModeEnabled) {
            SettingsSectionHeader("Developer Options")
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
    SettingsSectionHeader("Font Size")
    StepperRow(
        // roundToInt, not toInt: the summary row on the previous screen goes through
        // ReaderSettingsSummaries, so a truncating stepper here made the same scale read 115% up
        // there and 114% down here.
        label = ReaderSettingsSummaries.fontSizePercentLabel(prefs.fontSize),
        onDecrement = { onPrefsChange(prefs.copy(fontSize = (prefs.fontSize - 0.1f).coerceAtLeast(0.5f))) },
        onIncrement = { onPrefsChange(prefs.copy(fontSize = (prefs.fontSize + 0.1f).coerceAtMost(3.0f))) },
    )
    SettingsSectionHeader("Font Family")
    ChipRow(
        options = fontFamilyChipOptions,
        selected = prefs.fontFamily,
        label = { ReaderSettingsSummaries.fontFamilyLabel(it) },
        onSelect = { onPrefsChange(prefs.copy(fontFamily = it)) },
    )
    SettingsSectionHeader("Line Spacing")
    StepperRow(
        label = ReaderSettingsSummaries.lineSpacingCaption(prefs.lineSpacing),
        onDecrement = { onPrefsChange(prefs.copy(lineSpacing = (prefs.lineSpacing - 0.1f).coerceAtLeast(1.0f))) },
        onIncrement = { onPrefsChange(prefs.copy(lineSpacing = (prefs.lineSpacing + 0.1f).coerceAtMost(3.0f))) },
    )
    SettingsSectionHeader("Margins")
    StepperRow(
        label = ReaderSettingsSummaries.marginsCaption(prefs.margins),
        onDecrement = { onPrefsChange(prefs.copy(margins = (prefs.margins - 0.1f).coerceAtLeast(0.0f))) },
        onIncrement = { onPrefsChange(prefs.copy(margins = (prefs.margins + 0.1f).coerceAtMost(2.0f))) },
    )
    SettingsSectionHeader("Options")
    PanelToggleRow("Justify text", prefs.justifyText) { onPrefsChange(prefs.copy(justifyText = it)) }
}

@Composable
// `internal` rather than private so SettingsDisplayPanelTest can drive the real panel: the five
// On-Screen Info switches below are the only proof that the iOS Display panel offers them, and
// they cannot be asserted through a derivation.
internal fun DisplayPanelContent(prefs: FormattingPreferences, onPrefsChange: (FormattingPreferences) -> Unit) {
    SettingsSectionHeader("Reading Mode")
    ChipRow(
        options = readingModeChipOptions,
        selected = prefs.orientation,
        label = { ReaderSettingsSummaries.orientationWord(it) },
        onSelect = { onPrefsChange(prefs.copy(orientation = it)) },
    )
    SettingsSectionHeader("Landscape")
    PanelToggleRow("Force paginated in landscape", prefs.forcePaginatedInLandscape) {
        onPrefsChange(prefs.copy(forcePaginatedInLandscape = it))
    }
    PanelToggleRow("Double-page spread in landscape", prefs.doublePageSpread) {
        onPrefsChange(prefs.copy(doublePageSpread = it))
    }
    SettingsSectionHeader("Reader Theme")
    ChipRow(
        options = readerThemeChipOptions,
        selected = prefs.theme,
        label = { ReaderSettingsSummaries.themeLabel(it) },
        onSelect = { onPrefsChange(prefs.copy(theme = it)) },
    )
    SettingsSectionHeader("Auto Theme Mode")
    // "Time based"/"App theme" — the same words the Display summary row one screen up renders.
    // These chips used to read "Schedule"/"Follow app theme", so the same setting was named two
    // different things on the same screen.
    ChipRow(
        options = autoThemeModeChipOptions,
        selected = prefs.autoReaderThemeMode,
        label = { ReaderSettingsSummaries.autoModeLabel(it) },
        onSelect = { onPrefsChange(prefs.copy(autoReaderThemeMode = it)) },
    )
    // On-Screen Info — the five switches behind the chapter-map overlay the iOS reader now
    // renders (:feature:reader-ui's ChapterMapOverlay, mounted by IosEpubReaderScreen). Same
    // five flags, same order and same enablement rule as Android's DisplaySection.
    SettingsSectionHeader("On-Screen Info")
    PanelToggleRow("Chapter map", prefs.showChapterMap) { onPrefsChange(prefs.copy(showChapterMap = it)) }
    PanelToggleRow(
        label = "Colored chapter map",
        checked = prefs.coloredChapterMap,
        // Shared rule, not a second copy of "it depends on the parent": a sub-setting of the
        // chapter map greys out when the map is off.
        enabled = ReaderSettingsSections.coloredChapterMapEnabled(prefs.showChapterMap),
        onCheckedChange = { onPrefsChange(prefs.copy(coloredChapterMap = it)) },
    )
    PanelToggleRow("Current chapter label", prefs.showCurrentChapterLabel) {
        onPrefsChange(prefs.copy(showCurrentChapterLabel = it))
    }
    PanelToggleRow("Reading progress labels", prefs.showReadingProgressLabels) {
        onPrefsChange(prefs.copy(showReadingProgressLabels = it))
    }
    PanelToggleRow("Time remaining", prefs.showReadingTimeEstimate) {
        onPrefsChange(prefs.copy(showReadingTimeEstimate = it))
    }
}

// `internal` for the same reason as DisplayPanelContent: the switch and the stepper are only
// assertable by driving the real panel.
@Composable
internal fun AutoScrollPanelContent(prefs: FormattingPreferences, onPrefsChange: (FormattingPreferences) -> Unit) {
    SettingsSectionHeader("Auto-scroll")
    PanelToggleRow("Show auto-scroll toggle in reader", prefs.showAutoScroll) {
        onPrefsChange(prefs.copy(showAutoScroll = it))
    }
    SettingsSectionHeader("Speed (WPM)")
    // AutoScrollSpeed owns the range and the snap-to-10 rule; going through it rather than
    // hand-rolling the arithmetic is what keeps the stepper, the HUD pill's nudges and the
    // ticker agreeing on what a legal speed is.
    StepperRow(
        label = "${prefs.autoScrollWpm} WPM",
        onDecrement = {
            onPrefsChange(prefs.copy(autoScrollWpm = AutoScrollSpeed.of(prefs.autoScrollWpm - AutoScrollSpeed.STEP_WPM).wpm))
        },
        onIncrement = {
            onPrefsChange(prefs.copy(autoScrollWpm = AutoScrollSpeed.of(prefs.autoScrollWpm + AutoScrollSpeed.STEP_WPM).wpm))
        },
    )
}

/** Panel title and drill-in row label — one constant so the two can never disagree. */
internal const val CADENCE_PANEL_TITLE: String = "Cadence"

/**
 * What the panel shows instead of its controls when the reader's `Intl.Segmenter` probe came
 * back false. Cadence has no fallback tokeniser (issue #403), so there is nothing to configure.
 */
internal const val CADENCE_UNSUPPORTED_NOTE: String =
    "Cadence is not available on this device. A WebView update may enable it."

// `internal` for the same reason as AutoScrollPanelContent: the toggle, the stepper and the
// colour chips are only assertable by driving the real panel.
@Composable
internal fun CadencePanelContent(prefs: FormattingPreferences, onPrefsChange: (FormattingPreferences) -> Unit) {
    SettingsSectionHeader(CADENCE_PANEL_TITLE)
    if (!prefs.cadencePlatformSupported) {
        BasicText(
            text = CADENCE_UNSUPPORTED_NOTE,
            style = TextStyle(fontSize = 14.sp, color = Color.Gray),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    PanelToggleRow("Show cadence toggle in reader", prefs.showCadence) {
        onPrefsChange(prefs.copy(showCadence = it))
    }
    SettingsSectionHeader("Speed (WPM)")
    // AutoScrollSpeed owns the 80–600 range and the snap-to-10 rule, and Cadence deliberately
    // reuses it (issue #403) so the stepper, the HUD pill's nudges and the ticker agree on what
    // a legal speed is. The version of this panel that was removed hand-rolled ±10 clamped to
    // 50..1000 — a range the ticker would have rejected at both ends.
    StepperRow(
        label = "${prefs.cadenceWpm} WPM",
        onDecrement = {
            onPrefsChange(prefs.copy(cadenceWpm = AutoScrollSpeed.of(prefs.cadenceWpm - AutoScrollSpeed.STEP_WPM).wpm))
        },
        onIncrement = {
            onPrefsChange(prefs.copy(cadenceWpm = AutoScrollSpeed.of(prefs.cadenceWpm + AutoScrollSpeed.STEP_WPM).wpm))
        },
    )
    SettingsSectionHeader("Highlight Color")
    // Keyed on the HighlightColor value, never on its rendered label — and the options come from
    // the enum, so the picker cannot offer a colour the reader cannot paint.
    ChipRow(
        options = cadenceHighlightChipOptions,
        selected = prefs.cadenceHighlightColor,
        label = ::highlightColorLabel,
        onSelect = { onPrefsChange(prefs.copy(cadenceHighlightColor = it)) },
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
    SettingsSectionHeader("Playback Speed")
    // PlaybackSpeed is the domain's range and snap rule (0.5–3.0 in steps of 0.05). The private
    // arithmetic here truncated to one decimal, so 0.75 rendered "0.7×", and clamped at 4.0 —
    // a full 1.0× past what the player accepts.
    StepperRow(
        label = PlaybackSpeed.label(defaultPlaybackSpeed),
        onDecrement = { onSpeedChange(PlaybackSpeed.snap(defaultPlaybackSpeed - PlaybackSpeed.STEP)) },
        onIncrement = { onSpeedChange(PlaybackSpeed.snap(defaultPlaybackSpeed + PlaybackSpeed.STEP)) },
    )
    // The bounds come from SkipIntervals, which is also what clamps the value on its way to the
    // transport — so the stepper cannot offer an interval the player would refuse.
    SettingsSectionHeader("Skip Forward (seconds)")
    StepperRow(
        label = "${skipIntervalSeconds}s",
        onDecrement = { onSkipChange((skipIntervalSeconds - SKIP_STEP_SECONDS).coerceAtLeast(SkipIntervals.MIN_SECONDS)) },
        onIncrement = { onSkipChange((skipIntervalSeconds + SKIP_STEP_SECONDS).coerceAtMost(SkipIntervals.MAX_SECONDS)) },
    )
    SettingsSectionHeader("Rewind (seconds)")
    StepperRow(
        label = "${rewindIntervalSeconds}s",
        onDecrement = { onRewindChange((rewindIntervalSeconds - SKIP_STEP_SECONDS).coerceAtLeast(SkipIntervals.MIN_SECONDS)) },
        onIncrement = { onRewindChange((rewindIntervalSeconds + SKIP_STEP_SECONDS).coerceAtMost(SkipIntervals.MAX_SECONDS)) },
    )
    // Floors at 0, not MIN_SECONDS: 0 means "do not rewind on resume" and must stay reachable.
    SettingsSectionHeader("Rewind on Resume (seconds)")
    StepperRow(
        label = "${rewindOnResumeSeconds}s",
        onDecrement = { onRewindOnResumeChange((rewindOnResumeSeconds - SKIP_STEP_SECONDS).coerceAtLeast(0)) },
        onIncrement = { onRewindOnResumeChange((rewindOnResumeSeconds + SKIP_STEP_SECONDS).coerceAtMost(SkipIntervals.MAX_SECONDS)) },
    )
}

/** Stepper granularity for every interval in the Listening panel. */
private const val SKIP_STEP_SECONDS = 5

@Composable
private fun ComicDisplayPanelContent(
    prefs: ComicFormattingPreferences,
    onPrefsChange: (ComicFormattingPreferences) -> Unit,
) {
    SettingsSectionHeader("Background Theme")
    // Options and selection come from core:domain, the same set Android's ThemeChipRows renders
    // with includeAuto = true. This row used to offer the three concrete chips only and select on
    // the raw stored value, so a stored Auto (or DarkDim) highlighted nothing at all.
    ChipRow(
        options = ComicBackgroundThemeOptions,
        selected = comicBackgroundChipSelection(prefs.backgroundTheme),
        label = { ReaderSettingsSummaries.themeLabel(it) },
        onSelect = { onPrefsChange(prefs.copy(backgroundTheme = it.asComicBackgroundTheme())) },
    )
    SettingsSectionHeader("Panel View")
    PanelToggleRow("Enable panel view", prefs.panelViewOn) { onPrefsChange(prefs.copy(panelViewOn = it)) }
    if (prefs.panelViewOn) {
        // Order and wording from PanelOverflowOptions, the same table Android's radio group reads.
        // The chips used to run SPLIT → SMART_SPLIT → OFF with no descriptions at all.
        ChipRow(
            options = PanelOverflowOptions.ORDER,
            selected = prefs.panelOverflow,
            label = { PanelOverflowOptions.label(it) },
            onSelect = { onPrefsChange(prefs.copy(panelOverflow = it)) },
        )
        BasicText(
            text = PanelOverflowOptions.description(prefs.panelOverflow),
            style = TextStyle(fontSize = 12.sp, color = Color.Gray),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    SettingsSectionHeader("HUD")
    PanelToggleRow("Reading progress", prefs.showChapterMap) { onPrefsChange(prefs.copy(showChapterMap = it)) }
    PanelToggleRow("Page numbers", prefs.showPageProgress) { onPrefsChange(prefs.copy(showPageProgress = it)) }
}

// ── Primitive row components ──────────────────────────────────────────────────────────────────────

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
                    .testTag(TestTags.settingsTrailing(trailing))
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
private fun PanelToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.settingsPanelToggle(label))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            label,
            style = TextStyle(fontSize = 14.sp, color = if (enabled) Color.Unspecified else Color.Gray),
            modifier = Modifier.weight(1f),
        )
        BasicText(
            text = if (checked) "ON" else "OFF",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    !enabled -> Color.LightGray
                    checked -> Color(0xFF1565C0)
                    else -> Color.Gray
                },
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

/**
 * A row of single-choice chips keyed on the **value**, not on its rendered text.
 *
 * Every chip row on this screen used to build English literals and pick the active chip by string
 * equality against a shared derivation — `selected = prefs.theme.displayLabel()` against
 * `listOf("Light", "Dark", "Dim", …)`. One word changed in `ReaderThemeLabel.kt` and no chip would
 * highlight and every tap would silently do nothing, with nothing failing to say so. [label] is
 * for display only; selection and the tap callback both speak in [T].
 */
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
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
                    label(option),
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
//
// Every derivation this screen renders belongs to a shared module — feature:settings'
// ReaderSettingsSummaries / AnnotationSyncSubtitleText / AppUpdateStatusText, feature:player's
// PlaybackSpeed — so it renders exactly what Android's settings rows do. They used to be
// independent copies here and had all drifted: font labels read "Sans-serif"/"Monospace" against
// Android's "Sans serif"/"Mono"; the formatting row showed line spacing where Android shows
// margins; the font size used toInt() instead of roundToInt() (#1066); the annotation-sync
// subtitle differed on 7 of 9 branches; every app-update branch differed and three dropped their
// interpolated data; and the playback speed truncated to one decimal (0.75 → "0.7×") against a
// domain range whose step is 0.05.
//
// The composed strings below are `internal` rather than inline so `SettingsScreenDerivationsTest`
// can pin them — a Composable body is not reachable from a unit test, and these are exactly the
// call sites that drifted.

private fun AppTheme.label(): String = when (this) {
    AppTheme.Light -> "Light"
    AppTheme.Dark -> "Dark"
    AppTheme.System -> "System"
}

/** "Speed 1.25×" — the speed segment on its own, for callers that show nothing else. */
internal fun speedSummary(speed: Float): String = "Speed ${PlaybackSpeed.label(speed)}"

/** "Speed 1.25× · Skip 30s · Rewind 10s" — what the Listening drill-in previews. */
internal fun listeningSummary(speed: Float, skipSeconds: Int, rewindSeconds: Int): String =
    "${speedSummary(speed)} · Skip ${skipSeconds}s · Rewind ${rewindSeconds}s"

/**
 * The Readaloud row subtitle. Forwards to `readaloudRowSummary`, which suppresses zero counts and
 * has a "no readalouds yet" case — the private copy here printed "0 matched · 0 unmatched" instead
 * and dropped the username and server version the shared summary carries.
 */
internal fun readaloudSubtitle(
    storyteller: Source,
    serverVersions: Map<String, String>,
    readaloudSummaries: Map<String, ReadaloudMatchSummary>,
): String = readaloudRowSummary(storyteller, serverVersions, readaloudSummaries)

// Chip option lists, named so SettingsScreenDerivationsTest can assert they stay enum-backed.
// A hand-written list of English words is what made every tap on these rows a silent no-op the
// moment a label changed.
internal val readerThemeChipOptions: List<ReaderTheme> = ReaderTheme.entries.toList()
internal val readingModeChipOptions: List<ReaderOrientation> = ReaderOrientation.entries.toList()
internal val fontFamilyChipOptions: List<ReaderFontFamily> = ReaderFontFamily.entries.toList()
internal val autoThemeModeChipOptions: List<AutoReaderThemeMode> = AutoReaderThemeMode.entries.toList()
internal val cadenceHighlightChipOptions: List<HighlightColor> = HighlightColor.entries.toList()

/** "Yellow", "Green", … — the token capitalised, so the chip cannot drift from the stored value. */
internal fun highlightColorLabel(color: HighlightColor): String =
    color.token.replaceFirstChar { it.uppercase() }

/** The comic background-theme chip the stored preference should light up. */
internal fun comicBackgroundChipSelection(stored: ReaderTheme): ReaderTheme =
    stored.asComicBackgroundTheme()
