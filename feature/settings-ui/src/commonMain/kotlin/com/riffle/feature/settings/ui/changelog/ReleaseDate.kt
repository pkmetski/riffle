package com.riffle.feature.settings.ui.changelog

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// Month abbreviations for formatting without locale-aware java.time APIs.
private val MONTH_ABBREV = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

internal fun releaseDateLabel(publishedAt: String): String? {
    if (publishedAt.isBlank()) return null
    return runCatching {
        val local = Instant.parse(publishedAt).toLocalDateTime(TimeZone.currentSystemDefault())
        val monthName = MONTH_ABBREV.getOrElse(local.monthNumber - 1) { "?" }
        "$monthName ${local.dayOfMonth}, ${local.year}"
    }.getOrNull()
}

@Composable
internal fun ReleaseDateText(
    publishedAt: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    releaseDateLabel(publishedAt)?.let { label ->
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}
