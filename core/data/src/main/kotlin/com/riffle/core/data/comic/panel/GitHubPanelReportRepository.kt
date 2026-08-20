package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.PanelDetectionReport
import com.riffle.core.domain.comic.panel.PanelReportRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.UUID

class GitHubPanelReportRepository(
    private val pat: String,
    private val owner: String = "pkmetski",
    private val repoName: String = "riffle",
    private val client: HttpClient,
    private val apiBase: String = "https://api.github.com",
    private val rawBase: String = "https://raw.githubusercontent.com",
) : PanelReportRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun submit(report: PanelDetectionReport, maskPng: ByteArray): Result<String> =
        runCatching {
            val pngBase64 = java.util.Base64.getEncoder().encodeToString(maskPng)
            val filename = "panel-report-${UUID.randomUUID()}.png"

            // 1. Create blob
            val blobSha = post(
                "$apiBase/repos/$owner/$repoName/git/blobs",
                jsonObject("content" to pngBase64, "encoding" to "base64"),
            ).field("sha")

            // 2. Get current commit sha from panel-reports branch (create from main if absent)
            val currentCommitSha = try {
                get("$apiBase/repos/$owner/$repoName/git/ref/heads/panel-reports")
                    .let { it["object"]!!.jsonObject["sha"]!!.jsonPrimitive.content }
            } catch (e: IOException) {
                if ("404" !in (e.message ?: "")) throw e
                val mainSha = get("$apiBase/repos/$owner/$repoName/git/ref/heads/main")
                    .let { it["object"]!!.jsonObject["sha"]!!.jsonPrimitive.content }
                post(
                    "$apiBase/repos/$owner/$repoName/git/refs",
                    jsonObject("ref" to "refs/heads/panel-reports", "sha" to mainSha),
                )
                mainSha
            }

            // 3. Get current tree sha
            val commitJson = get("$apiBase/repos/$owner/$repoName/git/commits/$currentCommitSha")
            val currentTreeSha = commitJson["tree"]!!.jsonObject["sha"]!!.jsonPrimitive.content

            // 4. Create tree
            val newTreeSha = post(
                "$apiBase/repos/$owner/$repoName/git/trees",
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
                "$apiBase/repos/$owner/$repoName/git/commits",
                JsonObject(mapOf(
                    "message" to JsonPrimitive("panel report: ${report.failureType.label} p${report.pageIndex}"),
                    "tree" to JsonPrimitive(newTreeSha),
                    "parents" to JsonArray(listOf(JsonPrimitive(currentCommitSha))),
                )).toString(),
            ).field("sha")

            // 6. Advance ref
            patch(
                "$apiBase/repos/$owner/$repoName/git/refs/heads/panel-reports",
                jsonObject("sha" to newCommitSha),
            )

            val rawUrl = "$rawBase/$owner/$repoName/panel-reports/fixtures/$filename"

            // 7. Create issue
            post(
                "$apiBase/repos/$owner/$repoName/issues",
                JsonObject(mapOf(
                    "title" to JsonPrimitive("[Panel Detection] ${report.failureType.label} — page ${report.pageIndex}"),
                    "body" to JsonPrimitive(buildIssueBody(report, rawUrl)),
                    "labels" to JsonArray(listOf(JsonPrimitive("panel-view-issue"))),
                )).toString(),
            ).field("html_url")
        }

    private fun jsonObject(vararg pairs: Pair<String, String>): String =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) }).toString()

    private suspend fun get(url: String): JsonObject {
        val response = client.get(url) {
            header(HttpHeaders.Authorization, "token $pat")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        val bodyStr = response.bodyAsText()
        val obj = json.parseToJsonElement(bodyStr).jsonObject
        if (!response.status.isSuccess()) {
            val msg = obj["message"]?.jsonPrimitive?.content ?: response.status.description
            throw IOException("GitHub ${response.status.value}: $msg")
        }
        return obj
    }

    private suspend fun post(url: String, body: String): JsonObject {
        val response = client.post(url) {
            header(HttpHeaders.Authorization, "token $pat")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val bodyStr = response.bodyAsText()
        val obj = json.parseToJsonElement(bodyStr).jsonObject
        if (!response.status.isSuccess()) {
            val msg = obj["message"]?.jsonPrimitive?.content ?: response.status.description
            throw IOException("GitHub ${response.status.value}: $msg")
        }
        return obj
    }

    private suspend fun patch(url: String, body: String) {
        val response = client.patch(url) {
            header(HttpHeaders.Authorization, "token $pat")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            val bodyStr = response.bodyAsText()
            val msg = runCatching {
                json.parseToJsonElement(bodyStr).jsonObject["message"]?.jsonPrimitive?.content
            }.getOrNull() ?: response.status.description
            throw IOException("GitHub ${response.status.value}: $msg")
        }
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
        val order = report.expectedPanelOrder
        if (order != null) {
            appendLine("**Expected panel order:** $order")
            appendLine()
        }
        appendLine("**Detected panels:**")
        report.detectedPanels.forEachIndexed { i, p ->
            appendLine("- [$i] x=${p.x} y=${p.y} w=${p.width} h=${p.height}")
        }
        appendLine()
        if (report.drawnPanels.isNotEmpty()) {
            appendLine("**Expected panels (user-drawn):**")
            report.drawnPanels.forEachIndexed { i, p ->
                appendLine("- [$i] x=${p.x} y=${p.y} w=${p.width} h=${p.height}")
            }
            appendLine()
        }
        if (report.drawnBoundaries.isNotEmpty()) {
            appendLine("**Panel boundaries (user-drawn):**")
            report.drawnBoundaries.forEachIndexed { i, b ->
                appendLine("- [$i] (${b.x1},${b.y1}) → (${b.x2},${b.y2})")
            }
            appendLine()
        }
        appendLine("**Page image:**")
        appendLine("![page]($imageUrl)")
        appendLine()
        appendLine("---")
        appendLine("*panel-detection-issue · Filed automatically by Riffle panel detection reporter (ADR 0062)*")
    }
}
