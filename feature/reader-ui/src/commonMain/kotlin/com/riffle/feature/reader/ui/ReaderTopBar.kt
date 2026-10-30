package com.riffle.feature.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.reader.ui.generated.resources.Res
import com.riffle.feature.reader.ui.generated.resources.ui_annotations
import com.riffle.feature.reader.ui.generated.resources.ui_back
import com.riffle.feature.reader.ui.generated.resources.ui_format
import com.riffle.feature.reader.ui.generated.resources.ui_search
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
    extraActions: @Composable RowScope.() -> Unit,
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
                            contentDescription = stringResource(Res.string.ui_search),
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
                            RiffleIcons.Bookmarks,
                            contentDescription = stringResource(Res.string.ui_annotations),
                        )
                    }
                    val formatDescription = stringResource(Res.string.ui_format)
                    IconButton(
                        onClick = onFormat,
                        modifier = Modifier.testTag(TestTags.READER_SETTINGS),
                    ) {
                        Text(
                            text = "Aa",
                            color = Color.White,
                            style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                            modifier = Modifier.semantics { contentDescription = formatDescription },
                        )
                    }
                }
            },
        )
    }
}

/**
 * Text that wraps to up to 2 lines then shrinks its font size to fit,
 * using a drawWithContent guard so the oversized text never flickers on screen.
 */
@Composable
private fun AutoResizeText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val baseStyle = LocalTextStyle.current
    var resizedStyle by remember(text, baseStyle.fontSize) { mutableStateOf(baseStyle) }
    var readyToDraw by remember(text, baseStyle.fontSize) { mutableStateOf(false) }
    Text(
        text = text,
        color = color,
        style = resizedStyle,
        maxLines = 2,
        softWrap = true,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier.drawWithContent { if (readyToDraw) drawContent() },
        onTextLayout = { result ->
            if (result.hasVisualOverflow) {
                val next = resizedStyle.fontSize * 0.95f
                if (next.value >= 10f) {
                    resizedStyle = resizedStyle.copy(fontSize = next)
                } else {
                    resizedStyle = resizedStyle.copy(fontSize = 10.sp)
                    readyToDraw = true
                }
            } else {
                readyToDraw = true
            }
        },
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
