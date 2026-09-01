package com.riffle.core.data.comic.panel

import com.riffle.core.domain.comic.panel.PanelDetectionReport
import com.riffle.core.domain.comic.panel.PanelReportRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.header
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

class GitHubPanelReportRepository(
    private val pat: String,
    private val owner: String = "pkmetski",
    private val repoName: String = "riffle",
    private val client: HttpClient,
    private val apiBase: String = "https://api.github.com",
) : PanelReportRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun submit(report: PanelDetectionReport, maskPng: ByteArray): Result<String> =
        runCatching {
            val pngBase64 = java.util.Base64.getEncoder().encodeToString(maskPng)

            // 1. Create gist with mask (base64) and metadata
            val gistResponse = post("$apiBase/gists", buildGistBody(report, pngBase64))
            val gistHtmlUrl = gistResponse.field("html_url")
            val maskRawUrl = gistResponse["files"]!!.jsonObject["mask.b64"]!!
                .jsonObject["raw_url"]!!.jsonPrimitive.content

            // 2. Create issue linking the gist
            post(
                "$apiBase/repos/$owner/$repoName/issues",
                JsonObject(mapOf(
                    "title" to JsonPrimitive("[Panel Detection] ${report.failureType.label} — page ${report.pageIndex}"),
                    "body" to JsonPrimitive(buildIssueBody(report, gistHtmlUrl, maskRawUrl)),
                    "labels" to JsonArray(listOf(JsonPrimitive("panel-view-issue"))),
                )).toString(),
            ).field("html_url")
        }

    private fun buildGistBody(report: PanelDetectionReport, pngBase64: String): String =
        JsonObject(mapOf(
            "description" to JsonPrimitive(
                "Panel detection report: ${report.failureType.label} — page ${report.pageIndex}"
            ),
            "public" to JsonPrimitive(false),
            "files" to JsonObject(mapOf(
                "mask.b64" to JsonObject(mapOf("content" to JsonPrimitive(pngBase64))),
                "metadata.json" to JsonObject(mapOf("content" to JsonPrimitive(buildMetadata(report)))),
            )),
        )).toString()

    private fun buildMetadata(report: PanelDetectionReport): String =
        JsonObject(
            buildMap {
                put("pageIndex", JsonPrimitive(report.pageIndex))
                put("failureType", JsonPrimitive(report.failureType.label))
                put("detectedPanels", JsonArray(report.detectedPanels.map { p ->
                    JsonObject(mapOf(
                        "x" to JsonPrimitive(p.x),
                        "y" to JsonPrimitive(p.y),
                        "w" to JsonPrimitive(p.width),
                        "h" to JsonPrimitive(p.height),
                    ))
                }))
                report.expectedPanelOrder?.let { order ->
                    put("expectedPanelOrder", JsonArray(order.map { JsonPrimitive(it) }))
                }
                report.falsePanelIndices?.let { indices ->
                    put("falsePanelIndices", JsonArray(indices.map { JsonPrimitive(it) }))
                }
            }
        ).toString()

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

    private fun JsonObject.field(key: String): String = this[key]!!.jsonPrimitive.content

    private fun buildIssueBody(report: PanelDetectionReport, gistHtmlUrl: String, maskRawUrl: String): String = buildString {
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
        val falsePanels = report.falsePanelIndices
        if (falsePanels != null) {
            appendLine("**False panels:** $falsePanels")
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
        appendLine("**Mask fixture (gist):** $gistHtmlUrl")
        appendLine("**Mask raw URL:** `$maskRawUrl`")
        appendLine()
        appendLine("---")
        appendLine("*panel-detection-issue · Filed automatically by Riffle panel detection reporter (ADR 0062)*")
    }
}
