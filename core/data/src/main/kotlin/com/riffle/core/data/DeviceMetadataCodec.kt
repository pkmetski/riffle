package com.riffle.core.data

import com.riffle.core.domain.DeviceMetadata
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON helpers for the [DeviceMetadata] header embedded in each annotation file.
 *
 * Each per-device annotation file is a JSON array whose first element is a header object:
 * ```
 * [
 *   {"type":"riffle:DeviceMeta","deviceId":"...","label":"...","model":"...","lastSeenAt":"..."},
 *   { ...annotation 1... },
 *   { ...annotation 2... }
 * ]
 * ```
 *
 * The W3C annotation parser drops entries that don't carry an `id`, so the header is
 * automatically excluded from merge and from older readers — backwards-compatible.
 */
object DeviceMetadataCodec {

    /** Marker used on the header object so we can find it without an `id`. */
    const val HEADER_TYPE = "riffle:DeviceMeta"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** Render a [DeviceMetadata] as the header JSON object string. */
    fun encodeHeader(metadata: DeviceMetadata): String = buildJsonObject {
        put("type", HEADER_TYPE)
        put("deviceId", metadata.deviceId)
        put("label", metadata.label)
        put("lastSeenAt", metadata.lastSeenAt)
    }.toString()

    /**
     * Build the full annotation-file body as a JSON array: header first, then all annotation
     * JSON strings. The annotation strings are passed in already-serialised by
     * [AnnotationW3CCodec.annotationEntityToW3C].
     */
    fun buildFileBody(metadata: DeviceMetadata, annotationJsonStrings: List<String>): String {
        val header = encodeHeader(metadata)
        return if (annotationJsonStrings.isEmpty()) "[\n$header\n]"
        else "[\n$header,\n" + annotationJsonStrings.joinToString(",\n") + "\n]"
    }

    /**
     * Pull the [DeviceMetadata] header out of a serialised annotation-file body, if present.
     * Returns null when the body is malformed, isn't a JSON array, or carries no header object.
     */
    fun extractHeader(fileBody: String): DeviceMetadata? = try {
        val root = json.parseToJsonElement(fileBody)
        val elements: List<JsonElement> = when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        elements
            .mapNotNull { it as? JsonObject }
            .firstOrNull { (it["type"]?.jsonPrimitive?.content ?: "") == HEADER_TYPE }
            ?.let { headerObjectToMetadata(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Replace the existing [DeviceMetadata] header in [fileBody] with [metadata]. Used by
     * "rename this device" to refresh the label across every annotation file owned by this
     * device. Returns the original body unchanged when parsing fails.
     */
    fun replaceHeader(fileBody: String, metadata: DeviceMetadata): String {
        val root = try { json.parseToJsonElement(fileBody) } catch (_: Exception) { return fileBody }
        val elements: List<JsonElement> = when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> listOf(root)
            else -> return fileBody
        }
        val annotationsOnly = elements
            .filter { el ->
                val obj = el as? JsonObject ?: return@filter true
                (obj["type"]?.jsonPrimitive?.content ?: "") != HEADER_TYPE
            }
            .map { it.toString() }
        return buildFileBody(metadata, annotationsOnly)
    }

    private fun headerObjectToMetadata(obj: JsonObject): DeviceMetadata? {
        val deviceId = obj["deviceId"]?.jsonPrimitive?.content ?: return null
        val label = obj["label"]?.jsonPrimitive?.content ?: return null
        val lastSeenAt = obj["lastSeenAt"]?.jsonPrimitive?.content ?: return null
        return DeviceMetadata(deviceId, label, lastSeenAt)
    }
}
