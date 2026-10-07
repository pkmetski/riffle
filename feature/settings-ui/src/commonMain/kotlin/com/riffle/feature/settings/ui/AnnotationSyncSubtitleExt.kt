package com.riffle.feature.settings.ui
import androidx.compose.runtime.Composable
import com.riffle.feature.settings.AnnotationSyncSubtitle
import com.riffle.feature.settings.ui.generated.resources.*
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.withoutDanglingSeparator
import kotlinx.datetime.Clock
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AnnotationSyncSubtitle.resolve(): String = when (this) {
    is AnnotationSyncSubtitle.NotConfigured ->
        stringResource(Res.string.ui_webdav_not_configured_status)
    is AnnotationSyncSubtitle.WaitingForFirstSync ->
        stringResource(Res.string.ui_waiting_for_first_sync)
    is AnnotationSyncSubtitle.AuthFailed ->
        stringResource(Res.string.ui_webdav_auth_failed_reenter)
    is AnnotationSyncSubtitle.TlsError ->
        stringResource(Res.string.ui_webdav_tls_check_url)
    is AnnotationSyncSubtitle.HttpError ->
        stringResource(Res.string.ui_source_http_retry_short, code)
    is AnnotationSyncSubtitle.SyncFailed ->
        stringResource(Res.string.ui_sync_failed_retry_short)
    is AnnotationSyncSubtitle.BooksPendingOffline ->
        stringResource(Res.string.ui_books_pending_sync_online, count)
    is AnnotationSyncSubtitle.Offline ->
        stringResource(Res.string.ui_offline_sync_when_connected)
    // No bare "Synced" resource exists, so format the template with the relative-time argument
    // and drop the separator that strands when lastSyncMs is null (never synced).
    is AnnotationSyncSubtitle.Synced ->
        stringResource(Res.string.ui_synced_identity, relativeTimeCompose(lastSyncMs))
            .withoutDanglingSeparator()
}

@Composable
private fun relativeTimeCompose(lastSyncMs: Long?): String {
    if (lastSyncMs == null) return ""
    val elapsedSec = (Clock.System.now().toEpochMilliseconds() - lastSyncMs) / 1_000L
    return when {
        elapsedSec < 60 -> stringResource(Res.string.ui_synced_just_now)
        elapsedSec < 3_600 -> stringResource(Res.string.ui_synced_minutes_ago, elapsedSec / 60)
        elapsedSec < 86_400 -> stringResource(Res.string.ui_synced_hours_ago, elapsedSec / 3_600)
        else -> stringResource(Res.string.ui_synced_days_ago, elapsedSec / 86_400)
    }
}
