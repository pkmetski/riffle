package com.riffle.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Strips `riffle:deleted=true` records from a per-device annotation file body.
 *
 * Operates on the raw JSON-array file produced by [AnnotationSyncController.pushPending] so we
 * don't have to round-trip through [AnnotationW3CCodec] (which would silently drop unknown
 * fields that future schema versions add).
 */
object TombstoneCompactor {
    private val json = Json { ignoreUnknownKeys = true }

    /** Result returned by [compact]. */
    data class Result(val newContent: String, val removed: Int, val kept: Int)

    /** Returns the rewritten file body with tombstones removed, plus counts. */
    fun compact(originalContent: String): Result {
        val root = try {
            json.parseToJsonElement(originalContent)
        } catch (_: Exception) {
            return Result(originalContent, removed = 0, kept = -1)
        }
        val records: List<JsonElement> = when (root) {
            is JsonArray -> root.toList()
            is JsonObject -> listOf(root)
            else -> return Result(originalContent, removed = 0, kept = -1)
        }
        val kept = records.filter { element ->
            val obj = element as? JsonObject ?: return@filter true
            !isTombstone(obj)
        }
        val removed = records.size - kept.size
        if (removed == 0) return Result(originalContent, removed = 0, kept = kept.size)

        val newBody = if (kept.isEmpty()) {
            "[]"
        } else {
            "[\n" + kept.joinToString(",\n") { it.toString() } + "\n]"
        }
        return Result(newBody, removed = removed, kept = kept.size)
    }

    private fun isTombstone(obj: JsonObject): Boolean {
        val raw = obj["riffle:deleted"]?.jsonPrimitive?.content ?: return false
        return raw.equals("true", ignoreCase = true)
    }
}
