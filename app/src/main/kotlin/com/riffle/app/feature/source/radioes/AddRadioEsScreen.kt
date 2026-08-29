package com.riffle.app.feature.source.radioes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.riffle.app.R
import com.riffle.app.ui.TabletContentWidthContainer
import com.riffle.app.ui.source.SourceTypeIcon
import com.riffle.core.models.SourceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRadioEsScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: AddRadioEsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state) {
        if (state is AddRadioEsViewModel.State.Success) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SourceTypeIcon(type = SourceType.RADIO_ES, size = 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.ui_add_radio_es))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_back))
                    }
                },
            )
        },
    ) { padding ->
        TabletContentWidthContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.source_radio_es_name),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.ui_radio_es_source_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.ui_content_is_provided_by_radio_es),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                when (val s = state) {
                    is AddRadioEsViewModel.State.Idle -> {
                        Button(
                            onClick = viewModel::install,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.ui_add_source)) }
                    }
                    is AddRadioEsViewModel.State.Installing -> {
                        CircularProgressIndicator()
                    }
                    is AddRadioEsViewModel.State.Success -> {
                        CircularProgressIndicator()
                    }
                    is AddRadioEsViewModel.State.Error -> {
                        Text(
                            s.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = viewModel::install,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.ui_try_again)) }
                    }
                }
            }
        }
    }
}
