package com.riffle.app.feature.update

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun releaseDateLabel(
    publishedAt: String,
    locale: Locale = Locale.getDefault(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String? {
    if (publishedAt.isBlank()) return null
    return runCatching {
        val date = Instant.parse(publishedAt).atZone(zoneId).toLocalDate()
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(date)
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
