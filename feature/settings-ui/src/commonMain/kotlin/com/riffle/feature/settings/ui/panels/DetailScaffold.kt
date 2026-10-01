package com.riffle.feature.settings.ui.panels
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.*

import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import com.riffle.feature.designsystem.RiffleIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared full-screen scaffold for every Settings drill-in panel (Formatting, Display, Behavior,
 * Auto-Scroll, Cadence, Listening). Handles the top app bar, back handling, status/nav insets,
 * and vertical scroll. Panel-specific content renders inside the scrollable column.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun DetailScaffold(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Surface(modifier = Modifier.fillMaxSize().statusBarsPadding(), tonalElevation = 1.dp) {
        Column {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(RiffleIcons.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
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
