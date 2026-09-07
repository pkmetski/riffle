package com.riffle.shared.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.riffle.core.domain.AppTheme
import com.riffle.feature.settings.SettingsViewModel
import com.riffle.shared.AddAbsSourceScreen
import com.riffle.shared.AddSourceOption
import org.koin.compose.koinInject

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val viewModel = koinInject<SettingsViewModel>()
    val appTheme by viewModel.appTheme.collectAsState()
    val servers by viewModel.servers.collectAsState()
    var addingAbs by remember { mutableStateOf(false) }

    if (addingAbs) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { addingAbs = false }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                BasicText("← Settings", style = TextStyle(fontSize = 15.sp, color = Color(0xFF1565C0)))
            }
            AddAbsSourceScreen(onSourceAdded = { addingAbs = false })
        }
        return
    }

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

        // Sources section
        SectionHeader("Sources")
        if (servers.isEmpty()) {
            SettingsRow("No sources configured")
        } else {
            servers.forEach { source ->
                SettingsRow(
                    label = source.serverType.label,
                    subtitle = source.url.authority(),
                    trailing = "Remove",
                    onTrailingClick = { viewModel.removeServer(source.id) },
                )
            }
        }
        SettingsRow(
            label = AddSourceOption.Audiobookshelf.label,
            trailing = "Add",
            onTrailingClick = { addingAbs = true },
        )

        // Appearance section
        SectionHeader("Appearance")
        SettingsRow(
            label = "App Theme",
            subtitle = appTheme.label(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, bottom = 8.dp),
        ) {
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

        // Platform-specific sections
        PlatformSettingsSections()
    }
}

@Composable
private fun SectionHeader(title: String) {
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
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
                    .clickable { onTrailingClick?.invoke() }
                    .padding(start = 8.dp),
            )
        }
    }
}

private fun AppTheme.label(): String = when (this) {
    AppTheme.Light -> "Light"
    AppTheme.Dark -> "Dark"
    AppTheme.System -> "System"
}
