package com.riffle.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.riffle.app.R
import com.riffle.feature.settings.AnnotationSyncSubtitle
import com.riffle.feature.settings.withoutDanglingSeparator

@Composable
internal fun AnnotationSyncSubtitle.resolve(): String = when (this) {
    is AnnotationSyncSubtitle.NotConfigured ->
        stringResource(R.string.ui_webdav_not_configured_status)
    is AnnotationSyncSubtitle.WaitingForFirstSync ->
        stringResource(R.string.ui_waiting_for_first_sync)
    is AnnotationSyncSubtitle.AuthFailed ->
        stringResource(R.string.ui_webdav_auth_failed_reenter)
    is AnnotationSyncSubtitle.TlsError ->
        stringResource(R.string.ui_webdav_tls_check_url)
    is AnnotationSyncSubtitle.HttpError ->
        stringResource(R.string.ui_source_http_retry_short, code)
    is AnnotationSyncSubtitle.SyncFailed ->
        stringResource(R.string.ui_sync_failed_retry_short)
    is AnnotationSyncSubtitle.BooksPendingOffline ->
        stringResource(R.string.ui_books_pending_sync_online, count)
    is AnnotationSyncSubtitle.Offline ->
        stringResource(R.string.ui_offline_sync_when_connected)
    // No bare "Synced" resource exists, so format the identity template with an empty argument
    // and drop the separator it strands — the same derivation the shared `label()` runs, so the
    // two platforms cannot disagree about whether an identity-less row ends in " · ".
    is AnnotationSyncSubtitle.Synced ->
        stringResource(R.string.ui_synced_identity, identity ?: "").withoutDanglingSeparator()
}
