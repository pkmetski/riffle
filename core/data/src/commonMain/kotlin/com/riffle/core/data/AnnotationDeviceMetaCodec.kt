package com.riffle.core.data

import com.riffle.core.models.AnnotationDeviceMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON codec for the per-device metadata sentinel ([AnnotationDeviceMeta]).
 *
 * The file body is a single JSON object:
 * ```
 * {"type":"riffle:DeviceSyncMeta","deviceId":"...","label":"...","lastSyncedAt":"...","username":"..."}
 * ```
 *
 * Distinct `type` discriminator from the legacy `riffle:FileHeader` / `riffle:DeviceMeta`
 * embedded annotation-file header so a future reader can tell at a glance what schema it's
 * looking at and so the two never accidentally cross-parse.
 */
object AnnotationDeviceMetaCodec {

    const val TYPE = "riffle:DeviceSyncMeta"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** Render an [AnnotationDeviceMeta] as the JSON object body for `device-meta-<deviceId>.json`. */
    fun encode(meta: AnnotationDeviceMeta): String = buildJsonObject {
        put("type", TYPE)
        put("deviceId", meta.deviceId)
        put("label", meta.label)
        put("lastSyncedAt", meta.lastSyncedAt)
        meta.username?.takeIf { it.isNotBlank() }?.let { put("username", it) }
    }.toString()

    /** Parse a sentinel-file body. Returns null when the body is malformed or carries the wrong type. */
    fun decode(fileBody: String): AnnotationDeviceMeta? = try {
        val obj = json.parseToJsonElement(fileBody) as? JsonObject ?: return null
        if (obj["type"]?.jsonPrimitive?.content != TYPE) return null
        val deviceId = obj["deviceId"]?.jsonPrimitive?.content ?: return null
        val label = obj["label"]?.jsonPrimitive?.content ?: return null
        val lastSyncedAt = obj["lastSyncedAt"]?.jsonPrimitive?.content ?: return null
        val username = obj["username"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        AnnotationDeviceMeta(deviceId, label, lastSyncedAt, username)
    } catch (_: Exception) {
        null
    }
}
