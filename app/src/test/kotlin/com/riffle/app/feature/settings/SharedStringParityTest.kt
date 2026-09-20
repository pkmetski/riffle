package com.riffle.app.feature.settings

import com.riffle.core.domain.AvailableUpdate
import com.riffle.core.domain.comic.PanelOverflowBehavior
import com.riffle.feature.settings.AnnotationSyncSubtitle
import com.riffle.feature.settings.AppUpdateStatus
import com.riffle.feature.settings.AppUpdateUiState
import com.riffle.feature.settings.PanelOverflowOptions
import com.riffle.feature.settings.label
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one seam the shared derivations cannot close by themselves.
 *
 * Three settings surfaces exist twice on purpose: Android renders a `stringResource` because it
 * needs localization, iOS renders the untranslated shared derivation. That is the sanctioned
 * pattern — but it is also exactly how `AnnotationSyncSubtitle` came to differ on 7 of its 9
 * branches with both suites green, because nothing compared the two.
 *
 * This does. It reads `values/strings.xml` (the English source of truth every translation is
 * derived from), substitutes the positional arguments the way `stringResource` would, and asserts
 * the result equals what the shared derivation produces. Edit one side only and this flips red.
 */
class SharedStringParityTest {

    private val strings: Map<String, String> by lazy { readEnglishStrings() }

    // --- AnnotationSyncSubtitle (#1071 §16a) ---

    @Test
    fun `annotation sync subtitles match the English resources`() {
        assertResource("ui_webdav_not_configured_status", AnnotationSyncSubtitle.NotConfigured.label())
        assertResource("ui_waiting_for_first_sync", AnnotationSyncSubtitle.WaitingForFirstSync.label())
        assertResource("ui_webdav_auth_failed_reenter", AnnotationSyncSubtitle.AuthFailed.label())
        assertResource("ui_webdav_tls_check_url", AnnotationSyncSubtitle.TlsError.label())
        assertResource("ui_sync_failed_retry_short", AnnotationSyncSubtitle.SyncFailed.label())
        assertResource("ui_offline_sync_when_connected", AnnotationSyncSubtitle.Offline.label())
        assertResource("ui_source_http_retry_short", AnnotationSyncSubtitle.HttpError(503).label(), 503)
        assertResource("ui_books_pending_sync_online", AnnotationSyncSubtitle.BooksPendingOffline(3).label(), 3)
        assertResource("ui_synced_identity", AnnotationSyncSubtitle.Synced("me@host").label(), "me@host")
    }

    // --- AppUpdateUiState (#1071 §16b) ---

    @Test
    fun `app update status text matches the English resources`() {
        val update = AvailableUpdate("2.6.0", 260, "https://example.invalid/a.apk", 1L)
        fun status(state: AppUpdateUiState) = AppUpdateStatus.statusText(state, "2.5.0")

        assertResource("ui_installed_version", status(AppUpdateUiState.Idle), "2.5.0")
        assertResource("ui_checking_for_updates", status(AppUpdateUiState.Checking))
        assertResource("ui_installed_version_up_to_date", status(AppUpdateUiState.UpToDate), "2.5.0")
        assertResource(
            "ui_update_available_version",
            status(AppUpdateUiState.UpdateAvailable("2.6.0", update)),
            "2.6.0",
        )
        assertResource("ui_downloading_update_percent", status(AppUpdateUiState.Downloading(42)), 42)
        assertResource("ui_starting_installer", status(AppUpdateUiState.Installing))
        assertResource("ui_update_check_failed", status(AppUpdateUiState.Failed("no network")), "no network")
    }

    @Test
    fun `app update action labels match the English resources`() {
        val update = AvailableUpdate("2.6.0", 260, "https://example.invalid/a.apk", 1L)
        assertResource("ui_update", AppUpdateStatus.actionLabel(AppUpdateUiState.UpdateAvailable("2.6.0", update))!!)
        assertResource("ui_retry", AppUpdateStatus.actionLabel(AppUpdateUiState.Failed("x"))!!)
        assertResource("ui_check_for_updates", AppUpdateStatus.actionLabel(AppUpdateUiState.Idle)!!)
    }

    // --- PanelOverflowBehavior (#1071 §16l) ---

    @Test
    fun `panel overflow labels and descriptions match the English resources`() {
        assertResource("ui_no_split", PanelOverflowOptions.label(PanelOverflowBehavior.OFF))
        assertResource("ui_split", PanelOverflowOptions.label(PanelOverflowBehavior.SPLIT))
        assertResource("ui_smart_split", PanelOverflowOptions.label(PanelOverflowBehavior.SMART_SPLIT))
        assertResource(
            "ui_show_oversized_panels_as_is_without_splitting",
            PanelOverflowOptions.description(PanelOverflowBehavior.OFF),
        )
        assertResource(
            "ui_cuts_oversized_panels_in_half",
            PanelOverflowOptions.description(PanelOverflowBehavior.SPLIT),
        )
        assertResource(
            "ui_smart_split_description",
            PanelOverflowOptions.description(PanelOverflowBehavior.SMART_SPLIT),
        )
    }

    // --- plumbing ---

    private fun assertResource(name: String, shared: String, vararg args: Any) {
        val template = strings[name] ?: error("No <string name=\"$name\"> in values/strings.xml")
        val expected = String.format(java.util.Locale.ROOT, template, *args)
        assertEquals(
            "R.string.$name and the shared derivation have drifted apart",
            expected,
            shared,
        )
    }

    /**
     * Minimal `values/strings.xml` reader — enough for the plain `<string name="x">text</string>`
     * entries this test names. Unescapes the XML entities and the Android `\'` escape, and turns
     * `%%` into a literal `%` the way the platform's formatter does.
     */
    private fun readEnglishStrings(): Map<String, String> {
        val xml = stringsFile().readText()
        val pattern = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return pattern.findAll(xml).associate { m ->
            val value = m.groupValues[2]
                .replace("\\'", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
            m.groupValues[1] to value
        }
    }

    private fun stringsFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/res/values/strings.xml")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        val here = File("src/main/res/values/strings.xml").absoluteFile
        assertTrue("Could not locate app/src/main/res/values/strings.xml", here.isFile)
        return here
    }
}
