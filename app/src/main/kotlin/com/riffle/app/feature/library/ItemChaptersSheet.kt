package com.riffle.app.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.riffle.app.R
import com.riffle.app.feature.audiobook.CompactDurationLabelTemplates
import com.riffle.app.feature.audiobook.formatCompactDuration
import com.riffle.core.domain.AudiobookChapter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemChaptersSheet(
    chapters: List<AudiobookChapter>,
    onChapterClick: (AudiobookChapter) -> Unit,
    onDismiss: () -> Unit,
) {
    val durationLabels = CompactDurationLabelTemplates(
        minutes = stringResource(R.string.ui_duration_minutes_short),
        hours = stringResource(R.string.ui_duration_hours_short),
        hoursMinutes = stringResource(R.string.ui_duration_hours_minutes_short),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_chapters),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(chapters, key = { it.index }) { chapter ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            onChapterClick(chapter)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = chapter.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = chapter.startSec.toTimestamp(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = (chapter.endSec - chapter.startSec).toDuration(durationLabels),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun Double.toTimestamp(): String {
    val h = (this / 3600).toInt()
    val m = ((this % 3600) / 60).toInt()
    val s = (this % 60).roundToInt()
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun Double.toDuration(durationLabels: CompactDurationLabelTemplates): String =
    formatCompactDuration(this, durationLabels, roundToNearestMinute = true)
