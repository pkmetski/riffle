package com.riffle.app.feature.reader

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {

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
                    val selected = prefs.theme == theme
                    Button(
                        onClick = { onPrefsChange(prefs.copy(theme = theme)) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.semantics { contentDescription = "${theme.name} theme" },
                    ) { Text(theme.name) }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Font family
            Text("Font", style = MaterialTheme.typography.labelMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                ReaderFontFamily.entries.forEach { family ->
                    val selected = prefs.fontFamily == family
                    Button(
                        onClick = { onPrefsChange(prefs.copy(fontFamily = family)) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.semantics { contentDescription = "${family.name} font" },
                    ) { Text(family.name) }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Line spacing (Readium's effective range is 1.0–2.0)
            Text("Line spacing", style = MaterialTheme.typography.labelMedium)
            StepperRow(
                label = "%.1f×".format(prefs.lineSpacing),
                onDecrement = { onPrefsChange(prefs.copy(lineSpacing = (prefs.lineSpacing - 0.1f).coerceAtLeast(1.0f).round1())) },
                onIncrement = { onPrefsChange(prefs.copy(lineSpacing = (prefs.lineSpacing + 0.1f).coerceAtMost(2.0f).round1())) },
                decrementDescription = "Decrease line spacing",
                incrementDescription = "Increase line spacing",
            )

            Spacer(Modifier.height(16.dp))

            // Margins
            Text("Margins", style = MaterialTheme.typography.labelMedium)
            StepperRow(
                label = "%.1f×".format(prefs.margins),
                onDecrement = { onPrefsChange(prefs.copy(margins = (prefs.margins - 0.1f).coerceAtLeast(0.2f).round1())) },
                onIncrement = { onPrefsChange(prefs.copy(margins = (prefs.margins + 0.1f).coerceAtMost(2.0f).round1())) },
                decrementDescription = "Decrease margins",
                incrementDescription = "Increase margins",
            )

            Spacer(Modifier.height(16.dp))

            // Orientation
            Text("Reading mode", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderOrientation.entries.forEach { orientation ->
                    val selected = prefs.orientation == orientation
                    Button(
                        onClick = { onPrefsChange(prefs.copy(orientation = orientation)) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.semantics { contentDescription = "${orientation.name} mode" },
                    ) { Text(if (orientation == ReaderOrientation.Paginated) "Paginated" else "Scroll") }
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onReset,
                enabled = hasBookOverrides,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .alpha(if (hasBookOverrides) 1f else 0f),
            ) {
                Text("Reset to global defaults")
            }

            Spacer(Modifier.height(24.dp))
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilledTonalButton(
            onClick = onDecrement,
            modifier = Modifier.semantics { contentDescription = decrementDescription },
        ) { Text("−") }
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        FilledTonalButton(
            onClick = onIncrement,
            modifier = Modifier.semantics { contentDescription = incrementDescription },
        ) { Text("+") }
    }
}

private fun Float.round1() = (this * 10).roundToInt() / 10f
