package com.riffle.app.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

const val TAG_FOOTNOTE_POPUP = "footnote_popup"

private val POPUP_WIDTH = 300.dp
private val POPUP_MAX_HEIGHT = 200.dp
private val POPUP_VERTICAL_GAP = 12.dp

@Composable
fun FootnotePopup(
    state: FootnotePopupState,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewportHeightPx = with(density) { maxHeight.toPx() }
            val tapXDp: Dp = with(density) { state.tapX.toDp() }
            val tapYDp: Dp = with(density) { state.tapY.toDp() }

            val above = state.tapY > viewportHeightPx / 2f

            val idealLeft = tapXDp - 26.dp
            val clampedLeft = idealLeft.coerceIn(8.dp, maxWidth - POPUP_WIDTH - 8.dp)

            val cardOffset = if (above) {
                Modifier.offset(x = clampedLeft, y = tapYDp - POPUP_VERTICAL_GAP - POPUP_MAX_HEIGHT)
            } else {
                Modifier.offset(x = clampedLeft, y = tapYDp + POPUP_VERTICAL_GAP)
            }

            Surface(
                modifier = cardOffset
                    .testTag(TAG_FOOTNOTE_POPUP)
                    .width(POPUP_WIDTH)
                    .heightIn(max = POPUP_MAX_HEIGHT)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
            ) {
                Box {
                    Text(
                        text = state.content,
                        style = MaterialTheme.typography.bodySmall.copy(
                            lineHeight = 20.sp,
                        ),
                        modifier = Modifier
                            .padding(top = 12.dp, start = 14.dp, end = 40.dp, bottom = 14.dp)
                            .widthIn(max = POPUP_WIDTH)
                            .verticalScroll(rememberScrollState()),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close footnote",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
