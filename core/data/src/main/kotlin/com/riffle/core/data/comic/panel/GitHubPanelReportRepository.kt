package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.PanelDetectionReport
import com.riffle.core.domain.comic.panel.PanelReportRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class GitHubPanelReportRepository(
    private val pat: String,
    private val owner: String = "pkmetski",
    private val repoName: String = "riffle",
    private val client: OkHttpClient = OkHttpClient(),
    private val apiBase: String = "https://api.github.com",
    private val rawBase: String = "https://raw.githubusercontent.com",
) : PanelReportRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val base = apiBase

    override suspend fun submit(report: PanelDetectionReport, maskPng: ByteArray): Result<String> =
        withContext(Dispatchers.IO) { runCatching {
            val pngBase64 = java.util.Base64.getEncoder().encodeToString(maskPng)
            val filename = "panel-report-${UUID.randomUUID()}.png"

            // 1. Create blob
            val blobSha = post(
                "$base/repos/$owner/$repoName/git/blobs",
                jsonObject("content" to pngBase64, "encoding" to "base64"),
            ).field("sha")

            // 2. Get current commit sha from panel-reports branch
            val refJson = get("$base/repos/$owner/$repoName/git/ref/heads/panel-reports")
            val currentCommitSha = refJson["object"]!!.jsonObject["sha"]!!.jsonPrimitive.content

            // 3. Get current tree sha
            val commitJson = get("$base/repos/$owner/$repoName/git/commits/$currentCommitSha")
            val currentTreeSha = commitJson["tree"]!!.jsonObject["sha"]!!.jsonPrimitive.content

            // 4. Create tree
            val newTreeSha = post(
                "$base/repos/$owner/$repoName/git/trees",
                JsonObject(mapOf(
                    "base_tree" to JsonPrimitive(currentTreeSha),
                    "tree" to JsonArray(listOf(JsonObject(mapOf(
                        "path" to JsonPrimitive("fixtures/$filename"),
                        "mode" to JsonPrimitive("100644"),
                        "type" to JsonPrimitive("blob"),
                        "sha" to JsonPrimitive(blobSha),
                    )))),
                )).toString(),
            ).field("sha")

            // 5. Create commit
            val newCommitSha = post(
                "$base/repos/$owner/$repoName/git/commits",
                JsonObject(mapOf(
                    "message" to JsonPrimitive("panel report: ${report.failureType.label} p${report.pageIndex}"),
                    "tree" to JsonPrimitive(newTreeSha),
                    "parents" to JsonArray(listOf(JsonPrimitive(currentCommitSha))),
                )).toString(),
            ).field("sha")

            // 6. Advance ref
            patch(
                "$base/repos/$owner/$repoName/git/refs/heads/panel-reports",
                jsonObject("sha" to newCommitSha),
            )

            val rawUrl = "$rawBase/$owner/$repoName/panel-reports/fixtures/$filename"

            // 7. Create issue
            post(
                "$base/repos/$owner/$repoName/issues",
                JsonObject(mapOf(
                    "title" to JsonPrimitive("[Panel Detection] ${report.failureType.label} — page ${report.pageIndex}"),
                    "body" to JsonPrimitive(buildIssueBody(report, rawUrl)),
                    "labels" to JsonArray(listOf(JsonPrimitive("panel-detection-issue"), JsonPrimitive(report.failureType.githubLabel))),
                )).toString(),
            ).field("html_url")
        } }

    private fun jsonObject(vararg pairs: Pair<String, String>): String =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) }).toString()

    private fun get(url: String): JsonObject {
        val req = Request.Builder().url(url)
            .header("Authorization", "token $pat")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        return client.newCall(req).execute().use { resp ->
            json.parseToJsonElement(resp.body!!.string()).jsonObject
        }
    }

    private fun post(url: String, body: String): JsonObject {
        val req = Request.Builder().url(url)
            .header("Authorization", "token $pat")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(body.toRequestBody(jsonMedia))
            .build()
        return client.newCall(req).execute().use { resp ->
            json.parseToJsonElement(resp.body!!.string()).jsonObject
        }
    }

    private fun patch(url: String, body: String) {
        val req = Request.Builder().url(url)
            .header("Authorization", "token $pat")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .patch(body.toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { }
    }

    private fun JsonObject.field(key: String): String = this[key]!!.jsonPrimitive.content

    private fun buildIssueBody(report: PanelDetectionReport, imageUrl: String): String = buildString {
        appendLine("## Panel Detection Report")
        appendLine()
        appendLine("**Failure type:** ${report.failureType.label}")
        appendLine("**Page:** ${report.pageIndex}")
        appendLine("**Detected source:** ${report.detectedSource}")
        appendLine("**Panel count:** ${report.detectedPanels.size}")
        if (report.tappedX != null && report.tappedY != null) {
            appendLine("**Tapped at:** (${report.tappedX}, ${report.tappedY})")
            val idx = report.tappedPanelIndex
            if (idx != null) {
                val p = report.detectedPanels[idx]
                appendLine("**Selected panel $idx:** x=${p.x} y=${p.y} w=${p.width} h=${p.height}")
            }
        }
        appendLine()
        if (report.notes.isNotBlank()) {
            appendLine("**Notes:** ${report.notes}")
            appendLine()
        }
        appendLine("**Detected panels:**")
        report.detectedPanels.forEachIndexed { i, p ->
            appendLine("- [$i] x=${p.x} y=${p.y} w=${p.width} h=${p.height}")
        }
        appendLine()
        appendLine("**Sanitized page mask:**")
        appendLine("![sanitized page mask]($imageUrl)")
        appendLine()
        appendLine("---")
        appendLine("*Filed automatically by Riffle panel detection reporter (ADR 0062)*")
    }
}
