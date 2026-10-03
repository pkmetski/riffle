package com.riffle.feature.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.reader.ui.generated.resources.Res
import com.riffle.feature.reader.ui.generated.resources.ui_annotations
import com.riffle.feature.reader.ui.generated.resources.ui_back
import com.riffle.feature.reader.ui.generated.resources.ui_search_in_book
import com.riffle.feature.reader.ui.generated.resources.ui_table_of_contents
import org.jetbrains.compose.resources.stringResource

/**
 * Shared reader top bar for both Android and iOS. Slides in/out based on [visible].
 *
 * Core actions (Back, Search, TOC, Annotations, Format) are always available when the
 * navigator is ready. Additional platform-specific toggles (AutoScroll, Cadence, Readaloud,
 * Share) are injected via [extraActions].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    visible: Boolean,
    title: String,
    isReady: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onToc: () -> Unit,
    onAnnotations: () -> Unit,
    onFormat: () -> Unit,
    extraActions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Top),
        exit = shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = modifier,
    ) {
        TopAppBar(
            windowInsets = TopAppBarDefaults.windowInsets,
            colors = readerTopAppBarColors(),
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag(TestTags.READER_BACK),
                ) {
                    Icon(
                        RiffleIcons.ArrowBack,
                        contentDescription = stringResource(Res.string.ui_back),
                    )
                }
            },
            title = {
                AutoResizeText(
                    text = title,
                    color = Color.White,
                )
            },
            actions = {
                if (isReady) {
                    extraActions()
                    IconButton(
                        onClick = onSearch,
                        modifier = Modifier.testTag(TestTags.READER_SEARCH),
                    ) {
                        Icon(
                            RiffleIcons.Search,
                            contentDescription = stringResource(Res.string.ui_search_in_book),
                        )
                    }
                    IconButton(
                        onClick = onToc,
                        modifier = Modifier.testTag(TestTags.READER_TOC),
                    ) {
                        Icon(
                            RiffleIcons.FormatListNumbered,
                            contentDescription = stringResource(Res.string.ui_table_of_contents),
                        )
                    }
                    IconButton(
                        onClick = onAnnotations,
                        modifier = Modifier.testTag(TestTags.READER_ANNOTATIONS),
                    ) {
                        Icon(
                            RiffleIcons.Annotations,
                            contentDescription = stringResource(Res.string.ui_annotations),
                        )
                    }
                    IconButton(
                        onClick = onFormat,
                        modifier = Modifier.testTag(TestTags.READER_SETTINGS),
                    ) {
                        Text(
                            text = "Aa",
                            color = Color.White,
                            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            },
        )
    }
}

/** Single-line text that scales down to fit if the title is too long for the toolbar. */
@Composable
private fun AutoResizeText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = color,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Semi-transparent dark scrim matching Android's [com.riffle.app.ui.bottomBarScrimColor]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun readerTopAppBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = Color.Black.copy(alpha = 0.6f),
    titleContentColor = Color.White,
    navigationIconContentColor = Color.White,
    actionIconContentColor = Color.White,
)
