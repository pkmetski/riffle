package com.riffle.core.data

import com.riffle.core.common.FileStore
import com.riffle.core.domain.UpdateCheckResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the two iOS implementations that replaced the last no-ops (issue #1065): crash reports,
 * which previously always listed empty, and the update check, which previously always answered
 * UpToDate regardless of what had been released.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCrashAndUpdateTest {

    private val roots = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        roots.forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
    }

    private inner class TempFileStore : FileStore {
        private val root = NSTemporaryDirectory() + "crash_update_" + NSUUID().UUIDString()

        init {
            roots += root
        }

        override fun resolve(namespace: String, relativePath: String): String {
            val base = "$root/$namespace"
            IosAudiobookFiles.mkdirs(base)
            return if (relativePath.isEmpty()) base else "$base/$relativePath"
        }
    }

    // ── Crash reports ─────────────────────────────────────────────────────────

    @Test
    fun `no crashes recorded lists nothing`() {
        assertEquals(emptyList(), IosCrashReportRepositoryImpl(TempFileStore()).listCrashReports())
    }

    @Test
    fun `a recorded crash is listed with its id and content`() {
        val repo = IosCrashReportRepositoryImpl(TempFileStore())

        assertTrue(repo.record("crash-1000", "boom\nstack line"))

        val reports = repo.listCrashReports()
        assertEquals(1, reports.size)
        assertEquals("crash-1000", reports.single().id)
        assertTrue(reports.single().content.contains("boom"))
    }

    @Test
    fun `each crash gets its own report rather than overwriting`() {
        val repo = IosCrashReportRepositoryImpl(TempFileStore())

        repo.record("crash-1000", "first")
        repo.record("crash-2000", "second")

        assertEquals(setOf("crash-1000", "crash-2000"), repo.listCrashReports().map { it.id }.toSet())
    }

    @Test
    fun `report file paths resolve only for ids that exist`() {
        val repo = IosCrashReportRepositoryImpl(TempFileStore())
        repo.record("crash-1000", "first")

        val paths = repo.resolveReportFilePaths(listOf("crash-1000", "crash-absent"))

        assertEquals(1, paths.size)
        assertTrue(paths.single().endsWith("crash-1000.txt"))
    }

    @Test
    fun `clearing removes every report`() {
        val repo = IosCrashReportRepositoryImpl(TempFileStore())
        repo.record("crash-1000", "first")
        repo.record("crash-2000", "second")

        repo.clearAllCrashReports()

        assertEquals(emptyList(), repo.listCrashReports())
    }

    // ── The unhandled-exception hook body ─────────────────────────────────────
    //
    // In Kotlin/Native an installed hook REPLACES terminateWithUnhandledException, so a hook
    // that records and returns normally turns a crash into a log line and leaves the app
    // running in a corrupt state. These pin that the default processing always runs.

    @Test
    fun `with no prior hook the crash still terminates the process`() {
        var terminated: Throwable? = null

        onUnhandled(
            throwable = IllegalStateException("boom"),
            record = {},
            chain = null,
            terminate = { terminated = it },
        )

        assertEquals("boom", terminated?.message, "recording a crash must not downgrade it to a log line")
    }

    @Test
    fun `a prior hook is chained instead of terminating directly`() {
        var chained: Throwable? = null
        var terminated = false

        onUnhandled(
            throwable = IllegalStateException("boom"),
            record = {},
            chain = { chained = it },
            terminate = { terminated = true },
        )

        assertEquals("boom", chained?.message)
        assertFalse(terminated, "the prior hook owns termination once it has been handed the throwable")
    }

    @Test
    fun `a failure while recording does not stop the crash from being processed`() {
        var terminated = false

        onUnhandled(
            throwable = IllegalStateException("boom"),
            record = { throw RuntimeException("disk full") },
            chain = null,
            terminate = { terminated = true },
        )

        assertTrue(terminated, "recording is best-effort; termination is not")
    }

    @Test
    fun `the report carries the type and message and cause chain`() {
        val report = IosCrashReportRecorder.report(
            throwable = IllegalStateException("outer", IllegalArgumentException("inner")),
            timestampMillis = 1_700_000_000_000L,
        )

        assertTrue(report.contains("timestamp: 1700000000000"))
        assertTrue(report.contains("type: IllegalStateException"))
        assertTrue(report.contains("message: outer"))
        assertTrue(report.contains("Caused by: IllegalArgumentException: inner"))
    }

    // ── Update check ──────────────────────────────────────────────────────────

    private fun releasesJson(vararg tags: String) = tags.joinToString(prefix = "[", postfix = "]") { tag ->
        """{"tag_name":"$tag","draft":false,"prerelease":false,"body":"notes for $tag",""" +
            """"html_url":"https://example.test/$tag","published_at":"2026-01-01T00:00:00Z"}"""
    }

    private fun updateRepo(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        IosAppUpdateRepositoryImpl(
            httpClient = HttpClient(
                MockEngine {
                    if (status == HttpStatusCode.OK) {
                        respond(body, status, headersOf("Content-Type", "application/json"))
                    } else {
                        respondError(status)
                    }
                },
            ),
            apiBaseUrl = "https://api.test",
        )

    @Test
    fun `a newer published release is reported as available`() = runTest {
        val result = updateRepo(releasesJson("v1.2.3")).checkForUpdate(currentVersionCode = 10200)

        assertTrue(result is UpdateCheckResult.UpdateAvailable, "expected an update, got $result")
        assertEquals("1.2.3", result.update.versionName)
        assertEquals(10203, result.update.versionCode)
    }

    @Test
    fun `an equal or older release reports up to date`() = runTest {
        assertEquals(UpdateCheckResult.UpToDate, updateRepo(releasesJson("v1.2.3")).checkForUpdate(10203))
        assertEquals(UpdateCheckResult.UpToDate, updateRepo(releasesJson("v1.2.3")).checkForUpdate(20000))
    }

    @Test
    fun `the highest published tag wins regardless of list order`() = runTest {
        val result = updateRepo(releasesJson("v1.0.0", "v2.1.0", "v1.5.0")).checkForUpdate(10000)

        assertTrue(result is UpdateCheckResult.UpdateAvailable)
        assertEquals("2.1.0", result.update.versionName)
    }

    @Test
    fun `an unreachable GitHub reports failure rather than up to date`() = runTest {
        val result = updateRepo("", HttpStatusCode.ServiceUnavailable).checkForUpdate(10000)

        assertTrue(result is UpdateCheckResult.Failed, "a network failure must not look like up-to-date")
    }

    @Test
    fun `listReleasesSince returns only newer releases with their changelog`() = runTest {
        val releases = updateRepo(releasesJson("v1.0.0", "v1.1.0", "v1.2.0")).listReleasesSince(10100)

        assertEquals(listOf("1.2.0"), releases.map { it.versionName })
        assertEquals("notes for v1.2.0", releases.single().changelog)
    }

    @Test
    fun `self-update is not offered on iOS`() = runTest {
        val repo = updateRepo(releasesJson("v9.9.9"))
        val available = (repo.checkForUpdate(10000) as UpdateCheckResult.UpdateAvailable).update

        // iOS cannot install another build of itself; the flow completes without emitting and the
        // APK sweep is a no-op, which is the interface's documented non-Android behaviour.
        assertEquals(emptyList(), repo.downloadAndInstall(available).toList())
        repo.sweepStaleApks()
    }
}
