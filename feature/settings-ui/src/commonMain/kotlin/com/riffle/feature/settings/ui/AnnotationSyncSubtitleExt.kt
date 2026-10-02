package com.riffle.feature.settings.ui
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.*

import androidx.compose.runtime.Composable

import com.riffle.feature.settings.AnnotationSyncSubtitle
import com.riffle.feature.settings.withoutDanglingSeparator

@Composable
internal fun AnnotationSyncSubtitle.resolve(): String = when (this) {
    is AnnotationSyncSubtitle.NotConfigured ->
        // TODO: migrate to Res.string.ui_webdav_not_configured_status
        stringResource(Res.string.ui_webdav_not_configured_status)
    is AnnotationSyncSubtitle.WaitingForFirstSync ->
        // TODO: migrate to Res.string.ui_waiting_for_first_sync
        stringResource(Res.string.ui_waiting_for_first_sync)
    is AnnotationSyncSubtitle.AuthFailed ->
        // TODO: migrate to Res.string.ui_webdav_auth_failed_reenter
        stringResource(Res.string.ui_webdav_auth_failed_reenter)
    is AnnotationSyncSubtitle.TlsError ->
        // TODO: migrate to Res.string.ui_webdav_tls_check_url
        stringResource(Res.string.ui_webdav_tls_check_url)
    is AnnotationSyncSubtitle.HttpError ->
        // TODO: migrate to Res.string.ui_source_http_retry_short
        stringResource(Res.string.ui_source_http_retry_short, code)
    is AnnotationSyncSubtitle.SyncFailed ->
        // TODO: migrate to Res.string.ui_sync_failed_retry_short
        stringResource(Res.string.ui_sync_failed_retry_short)
    is AnnotationSyncSubtitle.BooksPendingOffline ->
        // TODO: migrate to Res.string.ui_books_pending_sync_online
        stringResource(Res.string.ui_books_pending_sync_online, count)
    is AnnotationSyncSubtitle.Offline ->
        // TODO: migrate to Res.string.ui_offline_sync_when_connected
        stringResource(Res.string.ui_offline_sync_when_connected)
    // No bare "Synced" resource exists, so format the identity template with an empty argument
    // and drop the separator it strands — the same derivation the shared `label()` runs, so the
    // two platforms cannot disagree about whether an identity-less row ends in " · ".
    is AnnotationSyncSubtitle.Synced ->
        // TODO: migrate to Res.string.ui_synced_identity
        stringResource(Res.string.ui_synced_identity, identity ?: "").withoutDanglingSeparator()
}
