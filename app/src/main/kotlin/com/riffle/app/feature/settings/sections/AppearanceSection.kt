package com.riffle.app.feature.settings.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.riffle.app.R
import com.riffle.app.feature.settings.SettingsSectionHeader
import com.riffle.app.i18n.AppLanguage
import com.riffle.core.domain.AppTheme

/**
 * Settings for app chrome. The app theme remains independent of the reader's content theme.
 */
@Composable
internal fun AppearanceSection(
    appTheme: AppTheme,
    onAppThemeChange: (AppTheme) -> Unit,
    appLanguage: AppLanguage,
    onAppLanguageChange: (AppLanguage) -> Unit,
) {
    SettingsSectionHeader(stringResource(R.string.ui_appearance))
    val options = listOf(
        AppTheme.Light to stringResource(R.string.ui_light),
        AppTheme.Dark to stringResource(R.string.ui_dark),
        AppTheme.System to stringResource(R.string.ui_system),
    )
    Column {
        Text(
            text = stringResource(R.string.ui_app_theme),
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
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

        var languageMenuOpen by remember { mutableStateOf(false) }
        val languageOptions = listOf(
            AppLanguage.System to stringResource(R.string.ui_system),
            AppLanguage.English to stringResource(R.string.ui_english),
            AppLanguage.Bulgarian to stringResource(R.string.ui_bulgarian),
            AppLanguage.Spanish to stringResource(R.string.ui_spanish_spain),
        )
        val selectedLanguageLabel = languageOptions.first { it.first == appLanguage }.second
        ListItem(
            modifier = Modifier.clickable { languageMenuOpen = true },
            headlineContent = { Text(stringResource(R.string.ui_app_language)) },
            supportingContent = { Text(selectedLanguageLabel) },
            trailingContent = {
                Box {
                    TextButton(onClick = { languageMenuOpen = true }) {
                        Text(selectedLanguageLabel)
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = stringResource(R.string.ui_open_menu),
                        )
                    }
                    DropdownMenu(
                        expanded = languageMenuOpen,
                        onDismissRequest = { languageMenuOpen = false },
                    ) {
                        languageOptions.forEach { (language, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    languageMenuOpen = false
                                    onAppLanguageChange(language)
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}
