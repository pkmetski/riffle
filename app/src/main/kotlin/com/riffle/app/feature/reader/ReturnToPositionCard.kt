package com.riffle.app.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

const val TAG_RETURN_CARD = "return_to_position_card"
const val TAG_RETURN_BACK = "return_to_position_back"
const val TAG_RETURN_DISMISS = "return_to_position_dismiss"

/**
 * A bottom card offering to undo an internal-link jump. Styled to match [FootnotePopup] (same
 * full-width Material surface, corner radius, and elevation) so the two reader cards read as one
 * family. Unlike the footnote popup it does NOT lay a full-screen scrim behind itself: the reader
 * stays interactive (you can turn pages while it's up), so only the surface itself captures taps —
 * the empty area above it has no gesture handler and falls through to the page.
 *
 * Tapping the "Back" body returns to the captured origin; the ✕ dismisses without navigating.
 */
@Composable
fun ReturnToPositionCard(
    onReturn: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .testTag(TAG_RETURN_CARD)
                .semantics { contentDescription = "Return to previous position" },
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onReturn)
                        .padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 12.dp)
                        .testTag(TAG_RETURN_BACK),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Back",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .testTag(TAG_RETURN_DISMISS),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
