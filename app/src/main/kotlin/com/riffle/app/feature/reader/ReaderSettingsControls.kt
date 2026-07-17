@file:OptIn(ExperimentalMaterial3Api::class)

package com.riffle.app.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.ThemeSchedule
import java.time.LocalTime
import kotlin.math.roundToInt

@Composable
internal fun ThemeSwatch(theme: ReaderTheme, schedule: ThemeSchedule) {
    if (theme == ReaderTheme.Auto) {
        AutoThemeSwatch(schedule)
    } else {
        ConcreteThemeSwatch(theme)
    }
}

@Composable
private fun ConcreteThemeSwatch(theme: ReaderTheme) {
    val palette = theme.palette
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(palette.background, RoundedCornerShape(percent = 50))
            .border(1.dp, palette.foreground, RoundedCornerShape(percent = 50)),
        contentAlignment = Alignment.Center,
    ) {
        Text("A", color = palette.foreground, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AutoThemeSwatch(schedule: ThemeSchedule) {
    val day = schedule.dayTheme.palette
    val night = schedule.nightTheme.palette
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(shape)
            .background(day.background, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            clipPath(path) {
                drawCircle(color = night.background, radius = size.minDimension / 2f)
            }
        }
    }
}

@Composable
internal fun OrientationIcon(orientation: ReaderOrientation) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    when (orientation) {
        ReaderOrientation.Horizontal -> {
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
            // Tall page with a seam — one long document with a visible chapter boundary.
            Canvas(modifier = Modifier.size(18.dp)) {
                val w = size.width * 0.60f
                val h = size.height * 0.92f
                val leftX = (size.width - w) / 2f
                val topY = (size.height - h) / 2f
                val seamH = size.height * 0.06f
                val halfH = (h - seamH) / 2f
                drawRect(color, topLeft = Offset(leftX, topY), size = Size(w, halfH))
                drawRect(color, topLeft = Offset(leftX, topY + halfH + seamH), size = Size(w, halfH))
            }
        }
        ReaderOrientation.Continuous -> {
            // Single uninterrupted tall page — chapters flow seamlessly.
            Canvas(modifier = Modifier.size(18.dp)) {
                val w = size.width * 0.60f
                val h = size.height * 0.92f
                val leftX = (size.width - w) / 2f
                val topY = (size.height - h) / 2f
                drawRect(color, topLeft = Offset(leftX, topY), size = Size(w, h))
            }
        }
    }
}

// Font and theme chips are laid out with manual `Row`s (split across two rows by the callers)
// rather than `FlowRow`. This is deliberate: the compile classpath's compose-foundation (1.7.5)
// and the runtime (1.9.2) disagree on FlowRow's signature (runtime added an Alignment.Vertical
// param), so calling FlowRow throws NoSuchMethodError at runtime. Do not "simplify" these into
// FlowRow.
@Composable
internal fun FontChipRow(
    fonts: List<ReaderFontFamily>,
    selected: ReaderFontFamily,
    onSelect: (ReaderFontFamily) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fonts.forEach { family ->
            val label = family.label()
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
    ReaderFontFamily.Original -> null
    ReaderFontFamily.Serif -> FontFamily.Serif
    ReaderFontFamily.SansSerif -> FontFamily.SansSerif
    ReaderFontFamily.Monospace -> FontFamily.Monospace
    ReaderFontFamily.Literata -> rememberAssetFontFamily("Literata")
    ReaderFontFamily.Merriweather -> rememberAssetFontFamily("Merriweather")
    ReaderFontFamily.OpenDyslexic -> rememberAssetFontFamily("OpenDyslexic")
}

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

internal fun Float.round1() = (this * 10).roundToInt() / 10f

// Read-only Auto-schedule summary for the in-reader Display tab, which cannot edit times.
@Composable
internal fun AutoScheduleSummaryCard(schedule: ThemeSchedule) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(autoScheduleSummary(schedule), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "Edit the schedule in Settings → Display",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun AutoScheduleControls(
    schedule: ThemeSchedule,
    onScheduleChange: (ThemeSchedule) -> Unit,
) {
    Column {
        Text("Day starts at", style = MaterialTheme.typography.labelMedium)
        TimeField(
            time = schedule.dayStart,
            contentDescription = "Day start time",
            onTimeChange = { onScheduleChange(schedule.copy(dayStart = it)) },
        )
        Spacer(Modifier.height(8.dp))
        Text("Night starts at", style = MaterialTheme.typography.labelMedium)
        TimeField(
            time = schedule.nightStart,
            contentDescription = "Night start time",
            onTimeChange = { onScheduleChange(schedule.copy(nightStart = it)) },
        )
        Spacer(Modifier.height(12.dp))
        Text("Day theme", style = MaterialTheme.typography.labelMedium)
        ConcreteThemeDropdown(
            selected = schedule.dayTheme,
            fieldContentDescription = "Day theme",
            onSelect = { onScheduleChange(schedule.copy(dayTheme = it)) },
        )
        Spacer(Modifier.height(8.dp))
        Text("Night theme", style = MaterialTheme.typography.labelMedium)
        ConcreteThemeDropdown(
            selected = schedule.nightTheme,
            fieldContentDescription = "Night theme",
            onSelect = { onScheduleChange(schedule.copy(nightTheme = it)) },
        )
    }
}

@Composable
private fun ConcreteThemeDropdown(
    selected: ReaderTheme,
    fieldContentDescription: String,
    onSelect: (ReaderTheme) -> Unit,
) {
    val concretes = listOf(
        ReaderTheme.Light, ReaderTheme.Dark, ReaderTheme.DarkDim, ReaderTheme.Sepia,
    )
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected.label(),
            onValueChange = {},
            readOnly = true,
            leadingIcon = { ThemeSwatch(selected, ThemeSchedule()) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
                .semantics { contentDescription = fieldContentDescription },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            concretes.forEach { theme ->
                val label = theme.label()
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { ThemeSwatch(theme, ThemeSchedule()) },
                    onClick = {
                        onSelect(theme)
                        expanded = false
                    },
                    modifier = Modifier.semantics { contentDescription = "$label theme" },
                )
            }
        }
    }
}

@Composable
private fun TimeField(
    time: LocalTime,
    contentDescription: String,
    onTimeChange: (LocalTime) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val label = "%02d:%02d".format(time.hour, time.minute)
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .semantics { this.contentDescription = contentDescription },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.height(48.dp).fillMaxWidth(),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (showPicker) {
        val state = rememberTimePickerState(
            initialHour = time.hour,
            initialMinute = time.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(state.hour, state.minute))
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
            text = { TimePicker(state = state) },
        )
    }
}
