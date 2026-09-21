package com.riffle.feature.source.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val BOOKMARK_ACTIVE_COLOR = Color(0xFFB5440E)
private const val BOOKMARK_IDLE_ALPHA = 0.18f

/**
 * A pentagon bookmark ribbon pinned at the top-right corner of the reading area.
 * Idle: very low-opacity (ambient). Active: fills with [BOOKMARK_ACTIVE_COLOR] + squish animation.
 * Hidden entirely when [isVisible] is false (non-ABS books, Storyteller-only).
 *
 * Lives here rather than in :app because BOTH platforms draw it: the Android EPUB/PDF readers and
 * the Android audiobook player render it directly, and the iOS audiobook player renders it through
 * :feature:player-ui's shared [com.riffle.feature.player.ui.AudiobookPlayerBody]. Keeping one
 * definition is what stops the two ribbons from drifting.
 */
@Composable
fun CornerBookmarkIndicator(
    isBookmarked: Boolean,
    isVisible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = if (isBookmarked) "Remove bookmark" else "Bookmark this page",
) {
    if (!isVisible) return

    val fillColor by animateColorAsState(
        targetValue = if (isBookmarked) {
            BOOKMARK_ACTIVE_COLOR
        } else {
            BOOKMARK_ACTIVE_COLOR.copy(alpha = BOOKMARK_IDLE_ALPHA)
        },
        animationSpec = tween(durationMillis = 180),
        label = "bookmarkFill",
    )
    val scaleY by animateFloatAsState(
        targetValue = if (isBookmarked) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "bookmarkScaleY",
    )

    Box(
        modifier = modifier
            .size(width = 24.dp, height = 32.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 32.dp)) {
            // Pentagon bookmark shape: full-width rectangle tapering to a V at the bottom.
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                lineTo(size.width / 2f, size.height * 0.80f)
                lineTo(0f, size.height)
                close()
            }
            scale(scaleX = 1f, scaleY = scaleY, pivot = Offset(size.width / 2, 0f)) {
                drawPath(path, color = fillColor)
            }
        }
    }
}
