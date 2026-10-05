package com.riffle.core.data

import com.riffle.core.models.AnnotationFileHeader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON helpers for the book-scoped [AnnotationFileHeader] embedded in each annotation file.
 *
 * Each per-device annotation file is a JSON array whose first element is a header object:
 * ```
 * [
 *   {"type":"riffle:FileHeader","deviceId":"...","bookTitle":"..."},
 *   { ...annotation 1... },
 *   { ...annotation 2... }
 * ]
 * ```
 *
 * The W3C annotation parser drops entries that don't carry an `id`, so the header is
 * automatically excluded from merge and from older readers — backwards-compatible.
 *
 * Legacy headers from earlier builds also carried device-scoped fields (`label`, `lastSeenAt`,
 * `username`). Those moved to the per-device sentinel ([AnnotationDeviceMetaCodec]) on this build;
 * on read we ignore the legacy fields here, and on write we don't emit them, so the headers in the
 * share converge on the slim shape after every device's next push.
 */
object AnnotationFileHeaderCodec {

    /** Marker used on the header object so we can find it without an `id`. */
    const val HEADER_TYPE = "riffle:FileHeader"

    /**
     * Pre-rename header marker. Read-side accepts it so files written by builds before the
     * AnnotationFileHeader rename still surface their metadata on Maintenance; write-side
     * never uses it. The transition is bounded — every push rewrites the file from scratch
     * with [HEADER_TYPE], so over time every file in the share converges on the new marker.
     */
    private const val LEGACY_HEADER_TYPE = "riffle:DeviceMeta"

    private val recognisedHeaderTypes = setOf(HEADER_TYPE, LEGACY_HEADER_TYPE)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** Render an [AnnotationFileHeader] as the header JSON object string. */
    fun encodeHeader(header: AnnotationFileHeader): String = buildJsonObject {
        put("type", HEADER_TYPE)
        put("deviceId", header.deviceId)
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
     * still surface their book title. Legacy device-scoped fields (label/lastSeenAt/username)
     * are silently ignored — they live in the per-device sentinel now. Returns null when the
     * body is malformed, isn't a JSON array, or carries no recognised header object.
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

    private fun headerObjectToHeader(obj: JsonObject): AnnotationFileHeader? {
        val deviceId = obj["deviceId"]?.jsonPrimitive?.content ?: return null
        val bookTitle = obj["bookTitle"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        return AnnotationFileHeader(deviceId, bookTitle)
    }
}
