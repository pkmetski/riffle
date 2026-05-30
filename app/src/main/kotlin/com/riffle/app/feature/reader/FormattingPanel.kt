package com.riffle.app.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormattingPanel(
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
    fullScreen: Boolean = false,
) {
    // Reader use: fixed half-height panel (not ModalBottomSheet) so scrolling stays inside
    // the panel — the reader pane behind must stay visible and undimmed to preview changes.
    // Settings use: full-screen panel with no scrim and system back to dismiss.
    BackHandler(enabled = fullScreen, onBack = onDismiss)
    Box(modifier = Modifier.fillMaxSize()) {
        if (!fullScreen) {
            // Transparent tap-catcher over the top half so tapping outside dismisses.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        Surface(
            modifier = Modifier
                .align(if (fullScreen) Alignment.TopCenter else Alignment.BottomCenter)
                .fillMaxWidth()
                .then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.fillMaxHeight(0.5f))
                .then(if (fullScreen) Modifier.statusBarsPadding() else Modifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = if (fullScreen) RectangleShape else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 1.dp,
            shadowElevation = if (fullScreen) 0.dp else 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (fullScreen) {
                    TopAppBar(
                        title = { Text("Reading settings") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                    )
                }
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .navigationBarsPadding(),
            ) {

            // Font size
            Text("Font size", style = MaterialTheme.typography.labelMedium)
            StepperRow(
                label = "%.0f%%".format(prefs.fontSize * 100),
                onDecrement = { onPrefsChange(prefs.copy(fontSize = (prefs.fontSize - 0.1f).coerceAtLeast(0.5f).round1())) },
                onIncrement = { onPrefsChange(prefs.copy(fontSize = (prefs.fontSize + 0.1f).coerceAtMost(2.5f).round1())) },
                decrementDescription = "Decrease font size",
                incrementDescription = "Increase font size",
            )

            Spacer(Modifier.height(16.dp))

            // Theme
            Text("Theme", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderTheme.entries.forEach { theme ->
                    val label = theme.displayName()
                    FilterChip(
                        selected = prefs.theme == theme,
                        onClick = { onPrefsChange(prefs.copy(theme = theme)) },
                        label = { Text(label) },
                        leadingIcon = { ThemeSwatch(theme) },
                        modifier = Modifier.semantics { contentDescription = "$label theme" },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Font family — split into two rows: generic web fonts above, bundled book fonts
            // below. Manual two-Row layout avoids FlowRow's compose-foundation version
            // ABI skew (compile classpath 1.7.5 vs runtime 1.9.2 added an Alignment.Vertical
            // parameter, causing NoSuchMethodError at runtime).
            Text("Font", style = MaterialTheme.typography.labelMedium)
            val genericFonts = listOf(
                ReaderFontFamily.Serif,
                ReaderFontFamily.SansSerif,
                ReaderFontFamily.Monospace,
            )
            val bundledFonts = listOf(
                ReaderFontFamily.Literata,
                ReaderFontFamily.Merriweather,
                ReaderFontFamily.OpenDyslexic,
            )
            FontChipRow(genericFonts, prefs.fontFamily) {
                onPrefsChange(prefs.copy(fontFamily = it))
            }
            Spacer(Modifier.height(4.dp))
            FontChipRow(bundledFonts, prefs.fontFamily) {
                onPrefsChange(prefs.copy(fontFamily = it))
            }

            Spacer(Modifier.height(12.dp))

            // Justify text — typography setting, grouped under Font.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Justify text",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = prefs.justifyText,
                    onCheckedChange = { onPrefsChange(prefs.copy(justifyText = it)) },
                )
            }

            Spacer(Modifier.height(16.dp))

            // Line spacing (Readium's effective range is 1.0–2.0)
            Text("Line spacing", style = MaterialTheme.typography.labelMedium)
            StepperRow(
                label = "${lineSpacingWord(prefs.lineSpacing)} · %.1f×".format(prefs.lineSpacing),
                onDecrement = { onPrefsChange(prefs.copy(lineSpacing = (prefs.lineSpacing - 0.1f).coerceAtLeast(1.0f).round1())) },
                onIncrement = { onPrefsChange(prefs.copy(lineSpacing = (prefs.lineSpacing + 0.1f).coerceAtMost(2.0f).round1())) },
                decrementDescription = "Decrease line spacing",
                incrementDescription = "Increase line spacing",
            )

            Spacer(Modifier.height(16.dp))

            // Margins
            Text("Margins", style = MaterialTheme.typography.labelMedium)
            StepperRow(
                label = "${marginsWord(prefs.margins)} · %.1f×".format(prefs.margins),
                onDecrement = { onPrefsChange(prefs.copy(margins = (prefs.margins - 0.1f).coerceAtLeast(0.2f).round1())) },
                onIncrement = { onPrefsChange(prefs.copy(margins = (prefs.margins + 0.1f).coerceAtMost(3.0f).round1())) },
                decrementDescription = "Decrease margins",
                incrementDescription = "Increase margins",
            )

            SectionDivider()

            // Reading orientation
            Text("Reading orientation", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderOrientation.entries.forEach { orientation ->
                    val label = when (orientation) {
                        ReaderOrientation.Horizontal -> "Paginated"
                        ReaderOrientation.Vertical -> "Scroll"
                    }
                    FilterChip(
                        selected = prefs.orientation == orientation,
                        onClick = { onPrefsChange(prefs.copy(orientation = orientation)) },
                        label = { Text(label) },
                        leadingIcon = { OrientationIcon(orientation) },
                        modifier = Modifier.semantics {
                            contentDescription = "$label reading orientation"
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Double page in landscape: always shown so the layout stays stable; disabled
            // (greyed out) in scroll mode where it has no effect.
            val doublePageEnabled = prefs.orientation == ReaderOrientation.Horizontal
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (doublePageEnabled) 1f else 0.38f),
            ) {
                Text(
                    "Double page in landscape",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = prefs.doublePageSpread,
                    onCheckedChange = { onPrefsChange(prefs.copy(doublePageSpread = it)) },
                    enabled = doublePageEnabled,
                )
            }

            SectionDivider()

            // Chapter Map toggle — per-book reading feature, grouped on its own so it
            // doesn't get lumped in with Layout settings above.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Chapter Map",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = prefs.showChapterMap,
                    onCheckedChange = { onPrefsChange(prefs.copy(showChapterMap = it)) },
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onReset,
                enabled = hasBookOverrides,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Reset to global defaults")
            }

            Spacer(Modifier.height(16.dp))

            // "Also while reading" section — global settings surfaced here for convenience;
            // changes write to the same global DataStore as the Settings screen.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f))
                Text(
                    "Also while reading",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(modifier = Modifier.weight(1f))
            }

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onKeepScreenOnChange(!keepScreenOn) },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keep screen on", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Applies to all books",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = keepScreenOn, onCheckedChange = onKeepScreenOnChange)
            }

            Spacer(Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onVolumeKeyNavigationEnabledChange(!volumeKeyNavigationEnabled) },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Volume key navigation", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Applies to all books",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = volumeKeyNavigationEnabled,
                    onCheckedChange = onVolumeKeyNavigationEnabledChange,
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = volumeKeyNavigationEnabled) { onInvertVolumeKeysChange(!invertVolumeKeys) }
                    .alpha(if (volumeKeyNavigationEnabled) 1f else 0.38f),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Invert volume keys", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Applies to all books",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = invertVolumeKeys,
                    onCheckedChange = onInvertVolumeKeysChange,
                    enabled = volumeKeyNavigationEnabled,
                )
            }

                Spacer(Modifier.height(24.dp))
            }
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    decrementDescription: String,
    incrementDescription: String,
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(48.dp),
        ) {
            IconButton(
                onClick = onDecrement,
                modifier = Modifier.semantics { contentDescription = decrementDescription },
            ) { Text("−", style = MaterialTheme.typography.titleLarge) }
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onIncrement,
                modifier = Modifier.semantics { contentDescription = incrementDescription },
            ) { Text("+", style = MaterialTheme.typography.titleLarge) }
        }
    }
}

// Subtle group separator. Less visual weight than a full HorizontalDivider — uses the
// outlineVariant colour so it reads as "section break" rather than "actionable boundary".
// Kept thin to avoid bloating the panel given we already have several sections.
@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
    Spacer(Modifier.height(16.dp))
}

private fun ReaderTheme.displayName(): String = when (this) {
    ReaderTheme.Light -> "Light"
    ReaderTheme.Dark -> "Dark"
    ReaderTheme.DarkDim -> "Dim"
    ReaderTheme.Sepia -> "Sepia"
}

// Reader pane background/foreground colors for each theme — used to render a small
// preview swatch on the corresponding FilterChip so users can pick a theme by sight
// rather than reading the label. The dot is filled with the background and outlined
// in the foreground so Dark vs Dark dim are visually distinct.
private fun ReaderTheme.swatchBackground(): Color = when (this) {
    ReaderTheme.Light -> Color(0xFFFFFFFF)
    ReaderTheme.Dark -> Color(0xFF1A1A1A)
    ReaderTheme.DarkDim -> Color(0xFF1A1A1A)
    ReaderTheme.Sepia -> Color(0xFFF5E6CC)
}

private fun ReaderTheme.swatchForeground(): Color = when (this) {
    ReaderTheme.Light -> Color(0xFF1A1A1A)
    ReaderTheme.Dark -> Color(0xFFFFFFFF)
    ReaderTheme.DarkDim -> Color(0xFFAAAAAA) // matches DARK_DIM_TEXT_COLOR in the mapper
    ReaderTheme.Sepia -> Color(0xFF5C4A2E)
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(theme.swatchBackground(), RoundedCornerShape(percent = 50))
            .border(1.dp, theme.swatchForeground(), RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "A",
            color = theme.swatchForeground(),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun OrientationIcon(orientation: ReaderOrientation) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    when (orientation) {
        ReaderOrientation.Horizontal -> {
            // Two side-by-side rectangles — facing pages.
            Canvas(modifier = Modifier.size(18.dp)) {
                val w = size.width * 0.42f
                val h = size.height * 0.85f
                val gap = size.width * 0.06f
                val topY = (size.height - h) / 2f
                val leftX = (size.width - (2 * w + gap)) / 2f
                drawRect(color, topLeft = Offset(leftX, topY), size = Size(w, h))
                drawRect(color, topLeft = Offset(leftX + w + gap, topY), size = Size(w, h))
            }
        }
        ReaderOrientation.Vertical -> {
            // Three stacked horizontal lines — scrolling content.
            Canvas(modifier = Modifier.size(18.dp)) {
                val w = size.width * 0.82f
                val h = size.height * 0.12f
                val leftX = (size.width - w) / 2f
                val totalH = h * 5  // 3 lines + 2 gaps of equal size
                val startY = (size.height - totalH) / 2f
                repeat(3) { i ->
                    drawRect(color, topLeft = Offset(leftX, startY + i * 2 * h), size = Size(w, h))
                }
            }
        }
    }
}

private fun lineSpacingWord(value: Float): String = when {
    value < 1.15f -> "Tight"
    value < 1.35f -> "Compact"
    value < 1.55f -> "Normal"
    value < 1.75f -> "Comfortable"
    value < 1.95f -> "Roomy"
    else -> "Spacious"
}

private fun marginsWord(value: Float): String = when {
    value < 0.5f -> "Edge"
    value < 0.85f -> "Tight"
    value < 1.25f -> "Normal"
    value < 1.75f -> "Comfortable"
    value < 2.35f -> "Roomy"
    else -> "Wide"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontChipRow(
    fonts: List<ReaderFontFamily>,
    selected: ReaderFontFamily,
    onSelect: (ReaderFontFamily) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fonts.forEach { family ->
            val label = family.displayName()
            FilterChip(
                selected = selected == family,
                onClick = { onSelect(family) },
                label = {
                    Text(
                        label,
                        fontFamily = family.previewFontFamily(),
                    )
                },
                modifier = Modifier.semantics { contentDescription = "$label font" },
            )
        }
    }
}

@Composable
private fun ReaderFontFamily.previewFontFamily(): FontFamily? = when (this) {
    ReaderFontFamily.Serif -> FontFamily.Serif
    ReaderFontFamily.SansSerif -> FontFamily.SansSerif
    ReaderFontFamily.Monospace -> FontFamily.Monospace
    ReaderFontFamily.Literata -> rememberAssetFontFamily("Literata")
    ReaderFontFamily.Merriweather -> rememberAssetFontFamily("Merriweather")
    ReaderFontFamily.OpenDyslexic -> rememberAssetFontFamily("OpenDyslexic")
}

// Fonts are bundled as Android assets (loaded by Readium via CSS for the reader pane).
// For the formatting panel chips we list assets/fonts/, find a matching Regular file
// for the requested family, and build a Compose FontFamily. Returns null if assets are
// missing (e.g. `make fonts` was not run) so the chip falls back to the default font.
@Composable
private fun rememberAssetFontFamily(familyPrefix: String): FontFamily? {
    val assetManager = LocalContext.current.assets
    return remember(familyPrefix) {
        runCatching {
            val files = assetManager.list("fonts").orEmpty()
            val match = files.firstOrNull {
                it.startsWith("$familyPrefix-Regular") && (it.endsWith(".ttf") || it.endsWith(".otf"))
            } ?: files.firstOrNull {
                it.startsWith(familyPrefix) && (it.endsWith(".ttf") || it.endsWith(".otf"))
            } ?: return@runCatching null
            FontFamily(Font(path = "fonts/$match", assetManager = assetManager))
        }.getOrNull()
    }
}

private fun ReaderFontFamily.displayName(): String = when (this) {
    ReaderFontFamily.Serif -> "Serif"
    ReaderFontFamily.SansSerif -> "Sans serif"
    ReaderFontFamily.Monospace -> "Mono"
    ReaderFontFamily.Literata -> "Literata"
    ReaderFontFamily.Merriweather -> "Merriweather"
    ReaderFontFamily.OpenDyslexic -> "Dyslexic"
}

private fun Float.round1() = (this * 10).roundToInt() / 10f
