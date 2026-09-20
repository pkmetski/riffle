package com.riffle.feature.source.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_clear_search
import com.riffle.feature.source.ui.generated.resources.ui_open_menu
import com.riffle.feature.source.ui.generated.resources.ui_search
import org.jetbrains.compose.resources.stringResource

/**
 * Unified always-visible search header used by all source browse screens (server libraries and
 * web sources), on both hosts. Renders two rows: a hamburger + optional source icon + source name
 * label, then a search field with a primary-colour underline and animated clear button.
 *
 * [drawerButtonModifier] is the seam for Android's system-gesture-exclusion rect: `:app` passes a
 * modifier that declares an exclusion over the hamburger so a quick tap near the left edge is not
 * mis-captured as a back-swipe. That needs `LocalView` and `android.graphics.Rect`, neither of
 * which exists in an android+iOS source set — and iOS has no equivalent concept, so it passes
 * nothing. Everything else about the header is identical on both platforms, which is the point:
 * before this moved, iOS had no source search field at all.
 */
@Composable
fun SourceBrowseHeader(
    sourceName: String,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    sourceIcon: (@Composable () -> Unit)? = null,
    drawerButtonModifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    // Claim initial focus on an invisible focusable so the BasicTextField never auto-focuses
    // on entry. clearFocus() alone races with Android's view-focus pass; assigning focus
    // explicitly is reliable.
    val initialFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        initialFocus.requestFocus()
        keyboardController?.hide()
    }
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(initialFocus)
                .focusable(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 16.dp),
        ) {
            IconButton(onClick = onOpenDrawer, modifier = drawerButtonModifier) {
                Icon(SourceUiIcons.Menu, contentDescription = stringResource(Res.string.ui_open_menu))
            }
            if (sourceIcon != null) {
                sourceIcon()
            }
            Text(
                text = sourceName.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        ) {
            Icon(
                imageVector = SourceUiIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            val underlineColor = MaterialTheme.colorScheme.primary
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 4.dp)
                    .drawBehind {
                        drawLine(
                            color = underlineColor,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    },
                decorationBox = { inner ->
                    Box {
                        if (searchQuery.isEmpty()) {
                            Text(
                                stringResource(Res.string.ui_search),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            AnimatedVisibility(visible = searchQuery.isNotEmpty()) {
                IconButton(
                    onClick = { onSearchQueryChange("") },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = SourceUiIcons.Close,
                        contentDescription = stringResource(Res.string.ui_clear_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        HorizontalDivider()
    }
}
