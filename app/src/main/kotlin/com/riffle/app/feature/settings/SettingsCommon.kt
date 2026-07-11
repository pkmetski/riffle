package com.riffle.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The colored header + divider pair that opens every top-level section on the Settings screen.
 * Extracting it removes an 8× copy-paste and locks the visual weight of every section header.
 */
@Composable
internal fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    HorizontalDivider()
}

/**
 * A clickable "opens a full-screen or bottom-sheet drill-in" row with a right-aligned "Edit" text
 * button — the shape used by every reader/pacing/listening panel row. Both the row body and the
 * trailing text button call [onClick] so the whole row is a valid tap target.
 */
@Composable
internal fun SettingsDrillInRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = leadingContent,
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { TextButton(onClick = onClick) { Text("Edit") } },
    )
}

/**
 * Material's "disabled row" tint applied uniformly to headline, supporting text, and trailing
 * icon. Previously inlined in three places with subtly different color sets, one of which only
 * dimmed the headline.
 */
@Composable
internal fun disabledListItemColors(): ListItemColors = ListItemDefaults.colors(
    headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
    trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
)

/**
 * The trailing chevron used by every full-screen drill-in row (Sources, Readaloud, WebDAV,
 * Diagnostics). Kept as a single composable so ktlint stops flagging identical trailing lambdas.
 */
@Composable
internal fun DrillInChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
    )
}

/**
 * The four bottom-sheet reader/pacing/listening panels the main settings screen can open.
 * Collapses the six previously-separate `showFooPanel: Boolean` flags into one state so the
 * screen only tracks a single "which panel is open" value.
 */
internal enum class SettingsPanel {
    Formatting,
    Display,
    Behavior,
    AutoScroll,
    Cadence,
    Listening,
}

/**
 * Storyteller status badge — grey headphones when not configured, tinted green when configured.
 * Shown on both the collapsed Readaloud settings row and inside the drill-in screen so the state
 * is legible at either altitude.
 */
@Composable
internal fun StorytellerBadge(configured: Boolean) {
    val (bg, fg) = if (configured) {
        Color(0x336CD591L) to Color(0xFF6CD591L)
    } else {
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Headphones,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** WebDAV sync-status badge — same four states as [AnnotationSyncRowState.Badge]. */
@Composable
internal fun AnnotationSyncBadge(badge: AnnotationSyncRowState.Badge) {
    val (bg, fg, glyph) = when (badge) {
        AnnotationSyncRowState.Badge.Local -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Outlined.CloudOff,
        )
        AnnotationSyncRowState.Badge.Synced -> Triple(
            Color(0x336CD591L),
            Color(0xFF6CD591L),
            Icons.Default.CheckCircle,
        )
        AnnotationSyncRowState.Badge.Pending -> Triple(
            Color(0x33F5B94CL),
            Color(0xFFF5B94CL),
            Icons.Default.Schedule,
        )
        AnnotationSyncRowState.Badge.Error -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.error,
            Icons.Default.Warning,
        )
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = glyph, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
    }
}
