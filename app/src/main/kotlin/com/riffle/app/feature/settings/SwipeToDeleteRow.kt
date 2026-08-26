package com.riffle.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/**
 * Row wrapper that reveals a red delete affordance when swiped end-to-start and invokes
 * [onDelete] on a full swipe. Used by every configured-source row in Settings so the delete
 * gesture behaves the same everywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeToDeleteRow(
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
                    imageVector = Icons.Default.Delete,
                    contentDescription = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_delete),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        content = { content() },
    )
}
