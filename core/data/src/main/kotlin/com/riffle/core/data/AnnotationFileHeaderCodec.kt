package com.riffle.core.data

import com.riffle.core.domain.AnnotationFileHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON helpers for the [AnnotationFileHeader] embedded in each annotation file.
 *
 * Each per-device annotation file is a JSON array whose first element is a header object:
 * ```
 * [
 *   {"type":"riffle:FileHeader","deviceId":"...","label":"...","lastSeenAt":"...","username":"..."},
 *   { ...annotation 1... },
 *   { ...annotation 2... }
 * ]
 * ```
 *
 * The W3C annotation parser drops entries that don't carry an `id`, so the header is
 * automatically excluded from merge and from older readers — backwards-compatible.
 */
object AnnotationFileHeaderCodec {

    /** Marker used on the header object so we can find it without an `id`. */
    const val HEADER_TYPE = "riffle:FileHeader"

    /**
     * Pre-rename header marker. Read-side accepts it so files written by builds before the
     * AnnotationFileHeader rename still surface their metadata on Maintenance; write-side
     * never uses it. The transition is bounded — every push rewrites the file from scratch
     * with [HEADER_TYPE], so over time every file in the share converges on the new marker.
     *
     * TODO(annotation-sync): remove this legacy marker — and all references to it in
     *  [recognisedHeaderTypes], [extractHeader], [replaceHeader], and any test fixtures —
     *  once we're confident every install has rewritten its files at least once. Safe to
     *  drop a few releases after the rename ships.
     */
    private const val LEGACY_HEADER_TYPE = "riffle:DeviceMeta"

    private val recognisedHeaderTypes = setOf(HEADER_TYPE, LEGACY_HEADER_TYPE)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** Render an [AnnotationFileHeader] as the header JSON object string. */
    fun encodeHeader(header: AnnotationFileHeader): String = buildJsonObject {
        put("type", HEADER_TYPE)
        put("deviceId", header.deviceId)
        put("label", header.label)
        put("lastSeenAt", header.lastSeenAt)
        header.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
        header.bookTitle?.takeIf { it.isNotBlank() }?.let { put("bookTitle", it) }
    }.toString()

    /**
     * Build the full annotation-file body as a JSON array: header first, then all annotation
     * JSON strings. The annotation strings are passed in already-serialised by
     * [AnnotationW3CCodec.annotationEntityToW3C].
     */
    fun buildFileBody(header: AnnotationFileHeader, annotationJsonStrings: List<String>): String {
        val headerJson = encodeHeader(header)
        return if (annotationJsonStrings.isEmpty()) "[\n$headerJson\n]"
        else "[\n$headerJson,\n" + annotationJsonStrings.joinToString(",\n") + "\n]"
    }

    /**
     * Pull the [AnnotationFileHeader] out of a serialised annotation-file body, if present.
     * Accepts both the current and legacy header markers so files written by older builds
     * still surface their metadata. Returns null when the body is malformed, isn't a JSON
     * array, or carries no recognised header object.
     */
    fun extractHeader(fileBody: String): AnnotationFileHeader? = try {
        val root = json.parseToJsonElement(fileBody)
        val elements: List<JsonElement> = when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        elements
            .mapNotNull { it as? JsonObject }
            .firstOrNull { (it["type"]?.jsonPrimitive?.content ?: "") in recognisedHeaderTypes }
            ?.let { headerObjectToHeader(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Replace the existing header in [fileBody] with [header]. Used by "rename this device" to
     * refresh the label across every annotation file owned by this device. Drops headers
     * carrying either the current or legacy marker so renaming through the transition leaves a
     * single clean header instead of stacking a new one on top of an old one. Returns the
     * original body unchanged when parsing fails.
     */
    fun replaceHeader(fileBody: String, header: AnnotationFileHeader): String {
        val root = try { json.parseToJsonElement(fileBody) } catch (_: Exception) { return fileBody }
        val elements: List<JsonElement> = when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> listOf(root)
            else -> return fileBody
        }
        val annotationsOnly = elements
            .filter { el ->
                val obj = el as? JsonObject ?: return@filter true
                (obj["type"]?.jsonPrimitive?.content ?: "") !in recognisedHeaderTypes
            }
            .map { it.toString() }
        return buildFileBody(header, annotationsOnly)
    }

    private fun headerObjectToHeader(obj: JsonObject): AnnotationFileHeader? {
        val deviceId = obj["deviceId"]?.jsonPrimitive?.content ?: return null
        val label = obj["label"]?.jsonPrimitive?.content ?: return null
        val lastSeenAt = obj["lastSeenAt"]?.jsonPrimitive?.content ?: return null
        val username = obj["username"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val bookTitle = obj["bookTitle"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        return AnnotationFileHeader(deviceId, label, lastSeenAt, username, bookTitle)
    }
}
