package com.riffle.app.feature.update

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.riffle.core.domain.ReleaseInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChangelogViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_release_history)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_back))
                    }
                },
            )
        },
    ) { padding ->
        when (val state = uiState) {
            is ChangelogUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
            is ChangelogUiState.Loaded -> if (state.releases.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_no_releases_found), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState()),
                ) {
                    state.releases.forEach { release ->
                        ReleaseEntry(release)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseEntry(release: ReleaseInfo) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (release.releaseUrl.isNotBlank()) {
            val linkColor = MaterialTheme.colorScheme.primary
            val annotated = remember(release.releaseUrl, release.versionName, linkColor) {
                buildAnnotatedString {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "release",
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                            linkInteractionListener = { uriHandler.openUri(release.releaseUrl) },
                        )
                    ) {
                        append("v${release.versionName}")
                    }
                }
            }
            Text(
                text = annotated,
                style = MaterialTheme.typography.titleSmall,
            )
        } else {
            Text(
                text = "v${release.versionName}",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        ReleaseDateText(release.publishedAt)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (release.changelog.isBlank()) {
                androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_no_release_notes)
            } else {
                release.changelog
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
