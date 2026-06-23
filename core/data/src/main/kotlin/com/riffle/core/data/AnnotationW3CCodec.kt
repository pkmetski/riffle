package com.riffle.core.data

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.W3CAnnotation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * W3C Web Annotation codec for serializing/deserializing Riffle annotations.
 *
 * ## W3C Web Annotation Format
 *
 * Annotations are serialized to W3C Web Annotation Profile format with these key structures:
 *
 * ### Selectors
 * - **FragmentSelector**: EPUB CFI (e.g., `epubcfi(/6/4[chap01]!/4/2/16,/1:0,/1:100)`)
 *   for precise localization within the book structure.
 * - **TextQuoteSelector**: Optional text snippet (with textBefore/textAfter context)
 *   as a fallback anchor when CFI-based navigation fails (e.g., during chapter restructuring).
 *
 * ### Riffle-specific extensions (riffle: namespace)
 * - `device`: Device ID that created this annotation (for merge attribution).
 * - `lastModifiedBy`: Device ID that last modified this annotation (for LWW merge).
 * - `deleted`: Tombstone flag indicating this annotation is marked for deletion.
 *
 * ### Timestamps
 * - `created`: ISO 8601 created timestamp.
 * - `modified`: ISO 8601 last-modified timestamp (used for last-write-wins merge).
 *
 * ## Task 5 Implementation Notes
 *
 * - annotationEntityToW3C: Convert AnnotationEntity to W3C JSON string.
 *   - Build a W3CAnnotation intermediate, then serialize to JSON.
 *   - Include FragmentSelector (CFI) and TextQuoteSelector (snippet).
 *   - Map color → body.value (highlight color indicator).
 *   - Map note → body (if present).
 *   - Embed device/lastModifiedBy/deleted in riffle: namespace.
 *
 * - w3cToAnnotationEntity: Parse W3C JSON string to AnnotationEntity.
 *   - Deserialize JSON to W3CAnnotation.
 *   - Extract CFI from FragmentSelector.
 *   - Extract textSnippet/context from TextQuoteSelector.
 *   - Apply LWW merge logic (compare updatedAt timestamps).
 *   - Return AnnotationEntity with all fields populated from W3C + local defaults.
 */
object AnnotationW3CCodec {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Converts milliseconds since epoch to ISO 8601 string.
     */
    private fun Long.toIso8601(): String {
        return Instant.ofEpochMilli(this).toString()
    }

    /**
     * Parses ISO 8601 string to milliseconds since epoch.
     */
    private fun String.fromIso8601(): Long {
        return try {
            Instant.parse(this).toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Converts an AnnotationEntity to W3C Web Annotation JSON string.
     *
     * @param entity The local annotation entity to serialize.
     * @return W3C-formatted JSON string ready for transmission.
     *
     * @see annotationEntityToW3C (Task 5): Implement conversion logic.
     */
    fun annotationEntityToW3C(entity: AnnotationEntity): String {
        val motivation = when (entity.type) {
            AnnotationEntity.TYPE_HIGHLIGHT -> "highlighting"
            AnnotationEntity.TYPE_BOOKMARK -> "bookmarking"
            else -> "commenting"
        }

        // Build the target selector array with FragmentSelector and TextQuoteSelector
        val selectorArray = buildJsonArray {
            // FragmentSelector: EPUB CFI
            add(buildJsonObject {
                put("type", "FragmentSelector")
                put("conformsTo", "http://idpf.org/epub/linking/cfi/epub-cfi.html")
                put("value", entity.cfi)
            })
            // TextQuoteSelector: text context for re-anchoring
            add(buildJsonObject {
                put("type", "TextQuoteSelector")
                put("exact", entity.textSnippet)
                put("prefix", entity.textBefore)
                put("suffix", entity.textAfter)
            })
        }

        // Build the target object
        val targetObject = buildJsonObject {
            put("source", "epub://item-${entity.itemId}")
            put("selector", selectorArray)
        }

        // Build the body object
        val bodyObject = buildJsonObject {
            put("type", "TextualBody")
            put("purpose", motivation)
            // For highlights, include the color; for bookmarks, use the title
            if (entity.type == AnnotationEntity.TYPE_HIGHLIGHT && entity.color.isNotEmpty()) {
                put("value", entity.color)
            }
            if (entity.type == AnnotationEntity.TYPE_BOOKMARK && entity.bookmarkTitle.isNotEmpty()) {
                put("value", entity.bookmarkTitle)
            }
            // Include note if present
            val noteValue = entity.note
            if (noteValue != null && noteValue.isNotEmpty()) {
                put("textContent", noteValue)
            }
        }

        // Build the main W3C annotation object
        val annotationObject = buildJsonObject {
            put("@context", "http://www.w3.org/ns/anno.jsonld")
            put("id", "urn:uuid:${entity.id}")
            put("type", "Annotation")
            put("motivation", motivation)
            put("target", targetObject)
            put("body", bodyObject)
            put("created", entity.createdAt.toIso8601())
            put("modified", entity.updatedAt.toIso8601())
            // Riffle-specific extensions
            put("riffle:originDeviceId", entity.originDeviceId)
            put("riffle:lastModifiedByDeviceId", entity.lastModifiedByDeviceId)
            put("riffle:updatedAt", entity.updatedAt)
            put("riffle:deleted", entity.deleted)
            if (entity.type == AnnotationEntity.TYPE_BOOKMARK && entity.bookmarkTitle.isNotEmpty()) {
                put("riffle:bookmarkTitle", entity.bookmarkTitle)
            }
        }

        return annotationObject.toString()
    }

    /**
     * Parses a W3C Web Annotation JSON string to AnnotationEntity.
     *
     * @param jsonString W3C-formatted JSON string from a sync source.
     * @return Parsed W3CAnnotation ready for merge and storage.
     *
     * @see w3cToAnnotationEntity (Task 5): Implement deserialization logic.
     */
    fun w3cToAnnotationEntity(jsonString: String): W3CAnnotation {
        try {
            val root = json.parseToJsonElement(jsonString).jsonObject

            // Extract id and remove "urn:uuid:" prefix
            val idRaw = root["id"]?.jsonPrimitive?.content ?: ""
            val id = idRaw.removePrefix("urn:uuid:")

            // Extract motivation and map to type
            val motivation = root["motivation"]?.jsonPrimitive?.content ?: ""
            val type = when (motivation) {
                "highlighting" -> AnnotationEntity.TYPE_HIGHLIGHT
                "bookmarking" -> AnnotationEntity.TYPE_BOOKMARK
                else -> AnnotationEntity.TYPE_HIGHLIGHT
            }

            // Extract target and selectors
            val target = root["target"]?.jsonObject
            var cfi = ""
            var textSnippet = ""
            var textBefore = ""
            var textAfter = ""
            var chapterHref = ""

            target?.let {
                chapterHref = it["source"]?.jsonPrimitive?.content?.removePrefix("epub://item-") ?: ""
                val selectors = it["selector"]?.jsonArray ?: emptyList()
                for (selector in selectors) {
                    val selectorObj = selector.jsonObject
                    when (selectorObj["type"]?.jsonPrimitive?.content) {
                        "FragmentSelector" -> {
                            cfi = selectorObj["value"]?.jsonPrimitive?.content ?: ""
                        }
                        "TextQuoteSelector" -> {
                            textSnippet = selectorObj["exact"]?.jsonPrimitive?.content ?: ""
                            textBefore = selectorObj["prefix"]?.jsonPrimitive?.content ?: ""
                            textAfter = selectorObj["suffix"]?.jsonPrimitive?.content ?: ""
                        }
                    }
                }
            }

            // Extract body fields
            val body = root["body"]?.jsonObject
            var color: String? = null
            var note: String? = null
            var bookmarkTitle: String? = null

            body?.let {
                val purpose = it["purpose"]?.jsonPrimitive?.content ?: ""
                val value = it["value"]?.jsonPrimitive?.content
                val textContent = it["textContent"]?.jsonPrimitive?.content

                if (purpose == "highlighting" && value != null) {
                    color = value
                } else if (purpose == "bookmarking" && value != null) {
                    bookmarkTitle = value
                }

                if (textContent != null) {
                    note = textContent
                }
            }

            // Extract timestamps and convert from ISO 8601 to millis
            val createdAtStr = root["created"]?.jsonPrimitive?.content ?: ""
            val createdAt = createdAtStr.fromIso8601()

            val modifiedAtStr = root["modified"]?.jsonPrimitive?.content ?: ""
            val updatedAt = modifiedAtStr.fromIso8601()

            // Extract Riffle extensions
            val originDeviceId = root["riffle:originDeviceId"]?.jsonPrimitive?.content ?: ""
            val lastModifiedByDeviceId = root["riffle:lastModifiedByDeviceId"]?.jsonPrimitive?.content ?: ""
            val deleted = root["riffle:deleted"]?.jsonPrimitive?.content?.toBoolean() ?: false

            // Use riffle:bookmarkTitle if available, otherwise from body
            val finalBookmarkTitle = root["riffle:bookmarkTitle"]?.jsonPrimitive?.content ?: (bookmarkTitle ?: "")

            return W3CAnnotation(
                id = id,
                cfi = cfi,
                textSnippet = textSnippet,
                textBefore = textBefore,
                textAfter = textAfter,
                chapterHref = chapterHref,
                type = type,
                color = color,
                note = note,
                bookmarkTitle = finalBookmarkTitle.ifEmpty { null },
                originDeviceId = originDeviceId,
                lastModifiedByDeviceId = lastModifiedByDeviceId,
                updatedAt = updatedAt,
                createdAt = createdAt,
                deleted = deleted,
            )
        } catch (e: Exception) {
            // Graceful degradation: return a minimal annotation if parsing fails
            return W3CAnnotation(
                id = "",
                cfi = "",
                textSnippet = "",
                chapterHref = "",
                type = AnnotationEntity.TYPE_HIGHLIGHT,
                originDeviceId = "",
                lastModifiedByDeviceId = "",
                updatedAt = 0L,
                createdAt = 0L,
            )
        }
    }
}
