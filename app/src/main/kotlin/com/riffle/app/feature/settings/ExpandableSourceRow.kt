package com.riffle.app.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

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
 */
@Composable
internal fun ExpandableSourceRow(
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRemove: () -> Unit,
    // Required (no default): every source row must brand itself with the source's logo, so future
    // sources are forced to wire one up (typically a SourceIcon / SourceTypeIcon). SourceIconResolver
    // owns the exhaustive-when that guarantees every SourceType maps to a bundled drawable.
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
                        modifier = Modifier.testTag("ExpandableSourceRow.LeadingIcon"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
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
