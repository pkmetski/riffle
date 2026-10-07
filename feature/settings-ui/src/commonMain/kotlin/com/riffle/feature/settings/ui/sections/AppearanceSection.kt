package com.riffle.feature.settings.ui.sections
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.AppTheme
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.SettingsSectionHeader
import com.riffle.feature.settings.ui.generated.resources.*
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.i18n.AppLanguage
import org.jetbrains.compose.resources.stringResource

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
    // TODO: migrate to Res.string.ui_appearance
    SettingsSectionHeader(stringResource(Res.string.ui_appearance))
    val options = listOf(
        // TODO: migrate to Res.string.ui_light
        AppTheme.Light to stringResource(Res.string.ui_light),
        // TODO: migrate to Res.string.ui_dark
        AppTheme.Dark to stringResource(Res.string.ui_dark),
        // TODO: migrate to Res.string.ui_system
        AppTheme.System to stringResource(Res.string.ui_system),
    )
    Column {
        Text(
            // TODO: migrate to Res.string.ui_app_theme
            text = stringResource(Res.string.ui_app_theme),
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
            // TODO: migrate to Res.string.ui_system
            AppLanguage.System to stringResource(Res.string.ui_system),
            // TODO: migrate to Res.string.ui_english
            AppLanguage.English to stringResource(Res.string.ui_english),
            // TODO: migrate to Res.string.ui_bulgarian
            AppLanguage.Bulgarian to stringResource(Res.string.ui_bulgarian),
            // TODO: migrate to Res.string.ui_spanish_spain
            AppLanguage.Spanish to stringResource(Res.string.ui_spanish_spain),
        )
        val selectedLanguageLabel = languageOptions.first { it.first == appLanguage }.second
        ListItem(
            modifier = Modifier.clickable { languageMenuOpen = true },
            // TODO: migrate to Res.string.ui_app_language
            headlineContent = { Text(stringResource(Res.string.ui_app_language)) },
            supportingContent = { Text(selectedLanguageLabel) },
            trailingContent = {
                Box {
                    TextButton(onClick = { languageMenuOpen = true }) {
                        Text(selectedLanguageLabel)
                        Icon(
                            RiffleIcons.ArrowDropDown,
                            // TODO: migrate to Res.string.ui_open_menu
                            contentDescription = stringResource(Res.string.ui_open_menu),
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
