package com.riffle.feature.reader.ui

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
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.reader.ui.generated.resources.Res
import com.riffle.feature.reader.ui.generated.resources.ui_back
import com.riffle.feature.reader.ui.generated.resources.ui_dismiss
import com.riffle.feature.reader.ui.generated.resources.ui_return_to_previous_position
import org.jetbrains.compose.resources.stringResource

/**
 * A bottom card offering to undo an internal-link jump. Unlike [FootnotePopup] it lays no
 * full-screen scrim behind itself: the reader stays interactive while it is up, so only the
 * surface itself captures taps.
 *
 * Tapping the "Back" body returns to the captured origin; the ✕ dismisses without navigating.
 */
@Composable
fun ReturnToPositionCard(
    onReturn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val returnContentDescription = stringResource(Res.string.ui_return_to_previous_position)
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .testTag(TestTags.READER_RETURN_CARD)
                .semantics { contentDescription = returnContentDescription },
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
                        .testTag(TestTags.READER_RETURN_BACK),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        RiffleIcons.ArrowBack,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(Res.string.ui_back),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .testTag(TestTags.READER_RETURN_DISMISS),
                ) {
                    Icon(
                        RiffleIcons.Close,
                        contentDescription = stringResource(Res.string.ui_dismiss),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
