package com.riffle.feature.settings

/**
 * The untranslated subtitle text for an [AnnotationSyncSubtitle].
 *
 * There was no shared derivation for this sealed class at all: Android resolved every branch
 * through `stringResource` (`AnnotationSyncSubtitleExt.kt`) and iOS carried a private `when` in
 * `SettingsScreen.kt` that had drifted on 7 of the 9 branches — "Not configured — local only"
 * against Android's "Not configured · Komga annotations, web-source reading progress",
 * "Sync failed" against "Sync failed · will retry automatically", and so on.
 *
 * This is now the single place the wording lives. Each branch mirrors the `values/strings.xml`
 * entry named in its comment, and `AnnotationSyncSubtitleStringsParityTest` in `:app` fails if
 * the two ever diverge again. Android keeps rendering the localized `stringResource` variant
 * because it needs a Compose composition; iOS renders this one.
 */
fun AnnotationSyncSubtitle.label(): String = when (this) {
    // R.string.ui_webdav_not_configured_status
    is AnnotationSyncSubtitle.NotConfigured ->
        "Not configured · Komga annotations, web-source reading progress"
    // R.string.ui_waiting_for_first_sync
    is AnnotationSyncSubtitle.WaitingForFirstSync -> "Waiting for first sync…"
    // R.string.ui_webdav_auth_failed_reenter
    is AnnotationSyncSubtitle.AuthFailed -> "Authentication failed · tap to re-enter credentials"
    // R.string.ui_webdav_tls_check_url
    is AnnotationSyncSubtitle.TlsError -> "TLS error · tap to check server URL"
    // R.string.ui_source_http_retry_short
    is AnnotationSyncSubtitle.HttpError -> "Source error (HTTP $code) · will retry automatically"
    // R.string.ui_sync_failed_retry_short
    is AnnotationSyncSubtitle.SyncFailed -> "Sync failed · will retry automatically"
    // R.string.ui_books_pending_sync_online
    is AnnotationSyncSubtitle.BooksPendingOffline -> "$count book(s) pending · will sync when online"
    // R.string.ui_offline_sync_when_connected
    is AnnotationSyncSubtitle.Offline -> "Offline · will sync when connected"
    // R.string.ui_synced_identity
    is AnnotationSyncSubtitle.Synced -> "Synced · ${identity ?: ""}".withoutDanglingSeparator()
}

/**
 * Drops the `·` a `"<word> · %1$s"` template leaves stranded when its argument is empty, so an
 * identity-less `Synced` reads "Synced" and not "Synced · ".
 *
 * Both platforms call this on the same string shape. Android cannot simply skip the template —
 * the translated word lives in `R.string.ui_synced_identity` and there is no bare "Synced"
 * resource — so it formats with an empty argument and trims here; the separator is the same `·`
 * in every locale the app ships, so the trim is locale-independent.
 */
fun String.withoutDanglingSeparator(): String = trimEnd().removeSuffix("·").trimEnd()
