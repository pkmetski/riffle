package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.core.domain.AppTheme

/**
 * "Appearance" section — currently one row: the app-chrome theme selector (Light / Dark / System).
 * Independent of the reader's content theme; drives the Material color scheme of every screen
 * outside the reading surface.
 */
@Composable
internal fun AppearanceSection(
    appTheme: AppTheme,
    onAppThemeChange: (AppTheme) -> Unit,
) {
    SettingsSectionHeader("Appearance")
    val options = listOf(
        AppTheme.Light to "Light",
        AppTheme.Dark to "Dark",
        AppTheme.System to "System",
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        options.forEachIndexed { index, (theme, label) ->
            SegmentedButton(
                selected = theme == appTheme,
                onClick = { onAppThemeChange(theme) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label)
            }
        }
    }
}
