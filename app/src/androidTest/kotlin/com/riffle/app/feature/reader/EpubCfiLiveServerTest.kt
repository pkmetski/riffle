package com.riffle.app.feature.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Integration tests against a live AudioBookshelf server at http://media-server:13378.
 *
 * All tests in this class are skipped gracefully (via assumeTrue) when the server is
 * unreachable, so CI passes on machines without network access to the server.
 *
 * To run these tests manually connect to the same network as media-server and execute:
 *   make harness-test  (or run this class directly from Android Studio)
 */
@RunWith(AndroidJUnit4::class)
class EpubCfiLiveServerTest {

    private val baseUrl = "http://media-server:13378"
    private val username = "plamen"
    private val password = "plamen"

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var token: String? = null
    private var libraryId: String? = null

    // ── Setup ─────────────────────────────────────────────────────────────────

    @Before
    fun setUp() {
        assumeTrue("Server must be reachable", isServerReachable())
        token = authenticate() ?: run {
            assumeTrue("Authentication failed — skipping live-server tests", false)
            return
        }
        libraryId = findEbookLibrary(token!!) ?: run {
            assumeTrue("No ebook library found — skipping live-server tests", false)
            return
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun translatorHandlesRealServerCfi() {
        val tok = token ?: return
        val libId = libraryId ?: return

        val itemId = findFirstEbookItemId(tok, libId) ?: run {
            assumeTrue("No ebook items found in library — skipping", false)
            return
        }

        val progress = getProgress(tok, itemId) ?: return
        val cfi = progress.optString("ebookLocation").takeIf { it.startsWith("epubcfi(") } ?: run {
            // No position stored yet — that's fine, skip the CFI translation check
            assumeTrue("No ebookLocation stored on server for item $itemId — skipping", false)
            return
        }

        val docPath = extractCfiDocPath(cfi)
        assertNotNull("extractCfiDocPath must parse real server CFI: $cfi", docPath)

        val spineIndex = epubCfiToSpineIndex(cfi)
        assertNotNull("epubCfiToSpineIndex must parse real server CFI: $cfi", spineIndex)

        // Download the EPUB and read the chapter HTML for the chapter the CFI points to
        val chapterHtml = downloadEpubChapterHtml(tok, itemId, spineIndex!!) ?: run {
            assumeTrue("Could not download EPUB for item $itemId — skipping", false)
            return
        }

        val progression = cfiDocPathToProgression(docPath!!, chapterHtml)
        assertNotNull(
            "cfiDocPathToProgression must succeed for real CFI $cfi in chapter $spineIndex",
            progression
        )
        assertTrue(
            "Progression must be in [0,1] for CFI $cfi, got $progression",
            progression!! in 0.0..1.0
        )
    }

    @Test
    fun outboundCfiRoundTripsWithRealEpub() {
        val tok = token ?: return
        val libId = libraryId ?: return

        val itemId = findFirstEbookItemId(tok, libId) ?: run {
            assumeTrue("No ebook items found — skipping", false)
            return
        }

        // Use spine index 0 (first chapter)
        val chapterHtml = downloadEpubChapterHtml(tok, itemId, spineIndex = 0) ?: run {
            assumeTrue("Could not download EPUB — skipping", false)
            return
        }

        val samples = listOf(0.0, 0.25, 0.5, 0.75)
        for (p in samples) {
            val path = progressionToCfiDocPath(p, chapterHtml) ?: continue
            val recovered = cfiDocPathToProgression(path, chapterHtml)
            assertNotNull("Round-trip returned null for p=$p, path=$path", recovered)
            assertTrue(
                "Round-trip error too large for p=$p: recovered=$recovered",
                kotlin.math.abs(p - recovered!!) < 0.05
            )
        }
    }

    @Test
    fun idAssertionsInOutboundCfiMatchRealEpubElements() {
        val tok = token ?: return
        val libId = libraryId ?: return

        val itemId = findFirstEbookItemId(tok, libId) ?: run {
            assumeTrue("No ebook items found — skipping", false)
            return
        }

        val chapterHtml = downloadEpubChapterHtml(tok, itemId, spineIndex = 0) ?: run {
            assumeTrue("Could not download EPUB — skipping", false)
            return
        }

        // Outbound path from some mid-chapter progression
        val path = progressionToCfiDocPath(0.5, chapterHtml) ?: return
        val ids = extractCfiElementIds(path)

        // For each ID in the emitted path, verify it actually exists in the chapter HTML
        for (id in ids) {
            assertTrue(
                "Outbound CFI emitted ID '$id' but it does not exist in the chapter HTML",
                hasElementWithId(chapterHtml, id)
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun isServerReachable(): Boolean = try {
        val req = Request.Builder().url("$baseUrl/ping").build()
        http.newCall(req).execute().use { it.isSuccessful }
    } catch (_: IOException) { false }

    private fun authenticate(): String? = try {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$baseUrl/login").post(body).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            JSONObject(resp.body!!.string())
                .optJSONObject("user")
                ?.optString("token")
                ?.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) { null }

    private fun findEbookLibrary(token: String): String? = try {
        val req = Request.Builder()
            .url("$baseUrl/api/libraries")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val libs = JSONObject(resp.body!!.string()).optJSONArray("libraries") ?: return null
            for (i in 0 until libs.length()) {
                val lib = libs.getJSONObject(i)
                if (lib.optString("mediaType") == "book") return lib.getString("id")
            }
            null
        }
    } catch (_: Exception) { null }

    private fun findFirstEbookItemId(token: String, libraryId: String): String? = try {
        val req = Request.Builder()
            .url("$baseUrl/api/libraries/$libraryId/items?limit=10")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val results = JSONObject(resp.body!!.string()).optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val ebookFormat = item.optJSONObject("media")?.optString("ebookFormat")
                if (ebookFormat == "epub") return item.getString("id")
            }
            null
        }
    } catch (_: Exception) { null }

    private fun getProgress(token: String, itemId: String): JSONObject? = try {
        val req = Request.Builder()
            .url("$baseUrl/api/me/progress/$itemId")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null
            else JSONObject(resp.body!!.string())
        }
    } catch (_: Exception) { null }

    private fun downloadEpubChapterHtml(token: String, itemId: String, spineIndex: Int): String? {
        return try {
            // First get the item to find the ebook file ino
            val itemReq = Request.Builder()
                .url("$baseUrl/api/items/$itemId")
                .header("Authorization", "Bearer $token")
                .build()
            val ino = http.newCall(itemReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body!!.string())
                    .optJSONObject("media")
                    ?.optJSONObject("ebookFile")
                    ?.optString("ino")
                    ?.takeIf { it.isNotEmpty() }
            } ?: return null

            // Download the EPUB bytes
            val epubReq = Request.Builder()
                .url("$baseUrl/api/items/$itemId/ebook/$ino")
                .header("Authorization", "Bearer $token")
                .build()
            val epubBytes = http.newCall(epubReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body!!.bytes()
            }

            // Write to a temp file and read the chapter at the requested spine index
            val context = androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().context
            val tmp = java.io.File(context.cacheDir, "live_server_cfi_test.epub")
            tmp.writeBytes(epubBytes)

            ZipFile(tmp).use { zip ->
                val opfEntry = zip.entries().asSequence()
                    .firstOrNull { it.name.endsWith("content.opf") } ?: return null
                val opfXml = zip.getInputStream(opfEntry).bufferedReader().readText()
                val spineHrefs = parseSpineHrefsFromOpf(opfXml)
                val chapterHref = spineHrefs.getOrNull(spineIndex) ?: return null
                val opfDir = opfEntry.name.substringBeforeLast('/', "")
                val entryPath = if (opfDir.isEmpty()) chapterHref else "$opfDir/$chapterHref"
                val chapterEntry = zip.getEntry(entryPath) ?: return null
                zip.getInputStream(chapterEntry).bufferedReader().readText()
            }
        } catch (_: Exception) { null }
    }

    private fun parseSpineHrefsFromOpf(opfXml: String): List<String> {
        // Minimal OPF parser: find spine idrefs in order, resolve to href via manifest
        val manifestItems = mutableMapOf<String, String>() // id → href
        val idRegex = Regex("""<item[^>]+id="([^"]+)"[^>]+href="([^"]+)"""")
        idRegex.findAll(opfXml).forEach { m ->
            manifestItems[m.groupValues[1]] = m.groupValues[2]
        }
        val spineOrder = mutableListOf<String>()
        val idrefRegex = Regex("""<itemref[^>]+idref="([^"]+)"""")
        idrefRegex.findAll(opfXml).forEach { m ->
            manifestItems[m.groupValues[1]]?.let { spineOrder.add(it) }
        }
        return spineOrder
    }
}
