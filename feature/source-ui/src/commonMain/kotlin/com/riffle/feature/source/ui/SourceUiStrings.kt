package com.riffle.feature.source.ui

import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.error_auth_failed_check_credentials
import com.riffle.feature.source.ui.generated.resources.error_connected_library_load_failed
import com.riffle.feature.source.ui.generated.resources.error_connection_failed
import com.riffle.feature.source.ui.generated.resources.error_couldnt_reach_server
import com.riffle.feature.source.ui.generated.resources.error_couldnt_save_source
import com.riffle.feature.source.ui.generated.resources.error_enter_valid_url
import com.riffle.feature.source.ui.generated.resources.error_parse_webdav_url
import com.riffle.feature.source.ui.generated.resources.error_source_http
import com.riffle.feature.source.ui.generated.resources.error_tls
import com.riffle.feature.source.ui.generated.resources.ui_books_pending_sync_shortly
import com.riffle.feature.source.ui.generated.resources.ui_books_pending_waiting_first_sync
import com.riffle.feature.source.ui.generated.resources.ui_couldnt_reach_server_retry
import com.riffle.feature.source.ui.generated.resources.ui_days_ago
import com.riffle.feature.source.ui.generated.resources.ui_hours_ago
import com.riffle.feature.source.ui.generated.resources.ui_just_now
import com.riffle.feature.source.ui.generated.resources.ui_minutes_ago
import com.riffle.feature.source.ui.generated.resources.ui_never
import com.riffle.feature.source.ui.generated.resources.ui_source_http_retry
import com.riffle.feature.source.ui.generated.resources.ui_sync_failed_retry
import com.riffle.feature.source.ui.generated.resources.ui_webdav_auth_failed_prescription
import com.riffle.feature.source.ui.generated.resources.ui_webdav_tls_failed_prescription
import org.jetbrains.compose.resources.getString

/**
 * The localized copy [AddSourceViewModel] and [SelectLibrariesViewModel] surface as *state*
 * (error banners, the WebDAV status card) rather than as inline `stringResource(…)` calls in the
 * composition.
 *
 * This exists because the Android originals took an `android.content.Context` purely to call
 * `context.getString(R.string.…)`. `Context` cannot cross into `commonMain`, and resolving the
 * copy in the composable instead would mean reshaping `error: String?` into a structured error
 * type — which would silently change what the 22 pinned `AddSourceViewModelTest` assertions are
 * claiming. Keeping a narrow suspend-returning seam preserves the exact strings while making the
 * ViewModels platform-neutral, and lets tests supply the copy without a resource loader.
 *
 * Methods are `suspend` because Compose Multiplatform resource lookup is suspending outside a
 * composition; every production call site is already inside a coroutine.
 */
interface SourceUiStrings {
    suspend fun enterValidUrl(): String
    suspend fun parseWebdavUrl(): String
    suspend fun authFailedCheckCredentials(): String
    suspend fun couldntReachServer(detail: String?): String
    suspend fun tlsError(detail: String?): String
    suspend fun sourceHttpError(code: Int): String
    suspend fun couldntSaveSource(detail: String?): String
    suspend fun connectionFailed(detail: String?): String
    suspend fun connectedLibraryLoadFailed(detail: String?): String
    suspend fun webdavAuthFailedPrescription(): String
    suspend fun webdavTlsFailedPrescription(): String
    suspend fun sourceHttpRetry(code: Int): String
    suspend fun syncFailedRetry(): String
    suspend fun couldntReachServerRetry(): String
    suspend fun booksPendingSyncShortly(count: Int): String
    suspend fun booksPendingWaitingFirstSync(count: Int): String
    suspend fun never(): String
    suspend fun justNow(): String
    suspend fun minutesAgo(minutes: Long): String
    suspend fun hoursAgo(hours: Long): String
    suspend fun daysAgo(days: Long): String
}

/**
 * Production [SourceUiStrings] — resolves the module's Compose Multiplatform string resources,
 * so Android and iOS get the same copy from the same `composeResources/values/strings.xml`.
 *
 * `%1$s` placeholders that used to receive a nullable `Throwable.message` are passed the raw
 * `null` rendering ("null") exactly as `Context.getString` did, so the visible text does not
 * change for the "cause has no message" case.
 */
object ComposeResourceSourceUiStrings : SourceUiStrings {
    override suspend fun enterValidUrl() = getString(Res.string.error_enter_valid_url)
    override suspend fun parseWebdavUrl() = getString(Res.string.error_parse_webdav_url)
    override suspend fun authFailedCheckCredentials() =
        getString(Res.string.error_auth_failed_check_credentials)
    override suspend fun couldntReachServer(detail: String?) =
        getString(Res.string.error_couldnt_reach_server, detail.toString())
    override suspend fun tlsError(detail: String?) =
        getString(Res.string.error_tls, detail.toString())
    override suspend fun sourceHttpError(code: Int) =
        getString(Res.string.error_source_http, code)
    override suspend fun couldntSaveSource(detail: String?) =
        getString(Res.string.error_couldnt_save_source, detail.toString())
    override suspend fun connectionFailed(detail: String?) =
        getString(Res.string.error_connection_failed, detail.toString())
    override suspend fun connectedLibraryLoadFailed(detail: String?) =
        getString(Res.string.error_connected_library_load_failed, detail.toString())
    override suspend fun webdavAuthFailedPrescription() =
        getString(Res.string.ui_webdav_auth_failed_prescription)
    override suspend fun webdavTlsFailedPrescription() =
        getString(Res.string.ui_webdav_tls_failed_prescription)
    override suspend fun sourceHttpRetry(code: Int) =
        getString(Res.string.ui_source_http_retry, code)
    override suspend fun syncFailedRetry() = getString(Res.string.ui_sync_failed_retry)
    override suspend fun couldntReachServerRetry() =
        getString(Res.string.ui_couldnt_reach_server_retry)
    override suspend fun booksPendingSyncShortly(count: Int) =
        getString(Res.string.ui_books_pending_sync_shortly, count)
    override suspend fun booksPendingWaitingFirstSync(count: Int) =
        getString(Res.string.ui_books_pending_waiting_first_sync, count)
    override suspend fun never() = getString(Res.string.ui_never)
    override suspend fun justNow() = getString(Res.string.ui_just_now)
    override suspend fun minutesAgo(minutes: Long) =
        getString(Res.string.ui_minutes_ago, minutes)
    override suspend fun hoursAgo(hours: Long) = getString(Res.string.ui_hours_ago, hours)
    override suspend fun daysAgo(days: Long) = getString(Res.string.ui_days_ago, days)
}
