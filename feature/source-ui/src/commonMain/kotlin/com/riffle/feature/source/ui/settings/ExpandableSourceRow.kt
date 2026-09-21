package com.riffle.feature.source.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.riffle.feature.source.ui.MaterialGlyphs
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_collapse
import com.riffle.feature.source.ui.generated.resources.ui_delete
import com.riffle.feature.source.ui.generated.resources.ui_expand
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import org.jetbrains.compose.resources.stringResource

/**
 * The one shell every source row in Settings uses: swipe-to-delete over a header ListItem with a
 * rotating chevron and a body that animates open on expand. Introducing a new source type means
 * passing its headline/subtitle and expanded content to this composable — the chevron rotation,
 * click-to-toggle, AnimatedVisibility, and the [Column] wrapper that keeps expanded rows from
 * stacking on top of each other are all handled here, so future rows get parity for free.
 *
 * The Column inside AnimatedVisibility is load-bearing: AnimatedVisibility's own container is a
 * [Box], so an [expandedContent] that emits multiple items directly would draw them all at the
 * same origin (this was the Chitanka library-row overlap bug).
 *
 * Lifted out of `:app` so iOS renders the same row; there is no Android copy left. The chevron's
 * "Expand"/"Collapse" description used to be a hardcoded English literal and is now localised
 * along with everything else here.
 */
@Composable
fun ExpandableSourceRow(
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRemove: () -> Unit,
    // Required (no default): every source row must brand itself with the source's logo, so future
    // sources are forced to wire one up (typically a SourceIcon / SourceTypeIcon).
    // SourceIconResolver owns the exhaustive-when that guarantees every SourceType maps to a
    // bundled drawable.
    leadingIcon: @Composable () -> Unit,
    headlineContent: @Composable () -> Unit,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    headerTestTag: String? = null,
    expandedContent: @Composable ColumnScope.() -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        label = "chevron",
    )
    Column {
        SwipeToDeleteRow(onDelete = onRemove) {
            ListItem(
                modifier = Modifier
                    .clickable { onToggleExpanded() }
                    .let { if (headerTestTag != null) it.testTag(headerTestTag) else it },
                leadingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            MaterialGlyphs.KeyboardArrowRight,
                            contentDescription = if (isExpanded) {
                                stringResource(Res.string.ui_collapse)
                            } else {
                                stringResource(Res.string.ui_expand)
                            },
                            modifier = Modifier.rotate(chevronRotation),
                        )
                        leadingIcon()
                    }
                },
                headlineContent = headlineContent,
                supportingContent = supportingContent,
                trailingContent = trailingContent,
            )
        }
        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = expandedContent,
            )
        }
    }
}

/**
 * Row wrapper that reveals a red delete affordance when swiped end-to-start and invokes
 * [onDelete] on a full swipe. Used by every configured-source row in Settings so the delete
 * gesture behaves the same everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // `enableDismissFromStartToEnd = false` (below) restricts the anchor set to EndToStart — the
    // deprecated `confirmValueChange` callback is no longer needed to veto other directions. The
    // caller owns the row lifecycle and removes it from the list after `onDelete`.
    //
    // `rememberSwipeToDismissBoxState` is Saveable, so the state can be restored to
    // `EndToStart` after process death mid-delete (before the caller had a chance to remove the
    // row). The old `confirmValueChange` callback was gesture-scoped and never fired on restore;
    // `drop(1)` on the current-value flow drops that first-composition emission so we match
    // the old behaviour and only fire `onDelete` on genuine user-driven transitions.
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.currentValue }
            .drop(1)
            .filter { it == SwipeToDismissBoxValue.EndToStart }
            .collect { onDelete() }
    }
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = MaterialGlyphs.Delete,
                    contentDescription = stringResource(Res.string.ui_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        content = { content() },
    )
}
