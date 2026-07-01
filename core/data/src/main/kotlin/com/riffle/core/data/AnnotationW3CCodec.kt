package com.riffle.core.data

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.W3CAnnotation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
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

        // Detect PDF rows. PDF locators are stored as Readium Locator JSON in
        // the `cfi` column (a historical-name field name; see the design spec
        // docs/superpowers/specs/2026-06-28-pdf-toc-and-annotations-design.md).
        // EPUB rows hold a raw epubcfi(...) string.
        val pdfLocator: JsonObject? = parsePdfLocator(entity.cfi)

        // Build the target selector array with format-specific selectors plus
        // a TextQuoteSelector for cross-device re-anchoring (same shape both
        // formats, so the receiving device can verify the annotation is
        // landing on the same text even when the source file was rebuilt).
        val selectorArray = buildJsonArray {
            if (pdfLocator != null) {
                add(buildPdfFragmentSelector(pdfLocator))
            } else {
                // FragmentSelector: EPUB CFI
                add(buildJsonObject {
                    put("type", "FragmentSelector")
                    put("conformsTo", "http://idpf.org/epub/linking/cfi/epub-cfi.html")
                    put("value", entity.cfi)
                })
            }
            // TextQuoteSelector: text context for re-anchoring
            add(buildJsonObject {
                put("type", "TextQuoteSelector")
                put("exact", entity.textSnippet)
                put("prefix", entity.textBefore)
                put("suffix", entity.textAfter)
            })
        }

        // Build the target object. Source scheme distinguishes book types so
        // a single annotations folder can host mixed EPUB+PDF rows for items
        // that might one day carry both (e.g. matched ABS items).
        val targetObject = buildJsonObject {
            val sourceScheme = if (pdfLocator != null) "pdf" else "epub"
            put("source", "$sourceScheme://item-${entity.itemId}")
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
            // Sort-key extension (see W3CAnnotation kdoc): keep the annotations panel in reading
            // order after a WebDAV round-trip. Derivable from the CFI but the merge orchestrator
            // is spine-agnostic, so we carry them explicitly.
            put("riffle:spineIndex", JsonPrimitive(entity.spineIndex))
            put("riffle:progression", JsonPrimitive(entity.progression))
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
    /**
     * In-memory projection of an [AnnotationEntity] to a [W3CAnnotation] without going through
     * the JSON intermediate. Used by `AnnotationSyncController.syncOnOpen` to feed local rows
     * (including tombstones) into the merge service so LWW spans local-and-remote, not just remote.
     */
    fun entityToW3CAnnotation(entity: AnnotationEntity): W3CAnnotation = W3CAnnotation(
        id = entity.id,
        cfi = entity.cfi,
        textSnippet = entity.textSnippet,
        textBefore = entity.textBefore,
        textAfter = entity.textAfter,
        chapterHref = entity.chapterHref,
        type = entity.type,
        color = entity.color.takeIf { it.isNotEmpty() },
        note = entity.note,
        bookmarkTitle = entity.bookmarkTitle.takeIf { it.isNotEmpty() },
        originDeviceId = entity.originDeviceId,
        lastModifiedByDeviceId = entity.lastModifiedByDeviceId,
        updatedAt = entity.updatedAt,
        createdAt = entity.createdAt,
        deleted = entity.deleted,
        spineIndex = entity.spineIndex,
        progression = entity.progression,
    )

    /**
     * Parse a per-device W3C JSON-LD file (a JSON array of annotations as written by
     * [pushPending][AnnotationSyncController]) into individual [W3CAnnotation]s. Tolerates a
     * single-object root for backward-compat with anything that wrote a bare object.
     */
    fun w3cFileToAnnotations(jsonString: String): List<W3CAnnotation> {
        val root = try {
            json.parseToJsonElement(jsonString)
        } catch (_: Exception) {
            return emptyList()
        }
        val objects: List<JsonObject> = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> return emptyList()
        }
        return objects.mapNotNull { obj ->
            val parsed = w3cObjectToAnnotation(obj)
            parsed.takeIf { it.id.isNotEmpty() }
        }
    }

    fun w3cToAnnotationEntity(jsonString: String): W3CAnnotation {
        return try {
            val root = json.parseToJsonElement(jsonString).jsonObject
            w3cObjectToAnnotation(root)
        } catch (_: Exception) {
            emptyAnnotation()
        }
    }

    private fun w3cObjectToAnnotation(root: JsonObject): W3CAnnotation {
        try {
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
                val src = it["source"]?.jsonPrimitive?.content ?: ""
                chapterHref = src.removePrefix("epub://item-").removePrefix("pdf://item-")
                val selectors = it["selector"]?.jsonArray ?: emptyList()
                for (selector in selectors) {
                    val selectorObj = selector.jsonObject
                    when (selectorObj["type"]?.jsonPrimitive?.content) {
                        "FragmentSelector" -> {
                            val conformsTo = selectorObj["conformsTo"]?.jsonPrimitive?.content ?: ""
                            cfi = if (conformsTo == PDF_FRAGMENT_CONFORMS_TO) {
                                // PDF: reassemble Locator JSON from FragmentSelector +
                                // RefinedBy DataPositionSelector + riffle:quads.
                                reassemblePdfLocator(
                                    fragmentSelector = selectorObj,
                                    chapterHref = chapterHref,
                                )
                            } else {
                                // EPUB: raw epubcfi(...) string.
                                selectorObj["value"]?.jsonPrimitive?.content ?: ""
                            }
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
            // Sort-key extension. Absent on files written before the extension existed — the
            // merge orchestrator falls back to any locally-known values (else 0/0.0) in that
            // case, so old files sort at the top on first receive.
            val spineIndex = root["riffle:spineIndex"]?.jsonPrimitive?.intOrNull ?: 0
            val progression = root["riffle:progression"]?.jsonPrimitive?.doubleOrNull ?: 0.0

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
                spineIndex = spineIndex,
                progression = progression,
            )
        } catch (_: Exception) {
            return emptyAnnotation()
        }
    }

    private fun emptyAnnotation(): W3CAnnotation = W3CAnnotation(
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

    // ---- PDF locator support -------------------------------------------------

    /**
     * IANA / IETF identifier for the PDF Open Parameters fragment syntax
     * (RFC 3778, used for `#page=N` style URL fragments). We use this as the
     * `conformsTo` on a FragmentSelector to mark a row as PDF-locatored.
     */
    private const val PDF_FRAGMENT_CONFORMS_TO = "http://tools.ietf.org/rfc/rfc3778"

    /**
     * Parses [cfi] as a Readium Locator JSON object, returning it if and only
     * if it's well-formed JSON whose `type` is `application/pdf`. EPUB rows
     * hold raw `epubcfi(...)` strings which fail JSON parsing; this returns
     * null for them and the encoder falls through to the EPUB branch.
     */
    private fun parsePdfLocator(cfi: String): JsonObject? {
        if (!cfi.startsWith("{")) return null
        return try {
            val root = json.parseToJsonElement(cfi) as? JsonObject ?: return null
            if (root["type"]?.jsonPrimitive?.content == "application/pdf") root else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build a W3C FragmentSelector for a PDF row. The selector encodes the
     * page number as a PDF Open Parameters fragment (`#page=N`, RFC 3778).
     * The character range is carried in a nested RefinedBy DataPositionSelector
     * (W3C Annotation Model §4.2.6). Persisted highlight quads — derived data
     * we don't want to recompute on every render — ride along as a Riffle-
     * namespaced property on the DataPositionSelector; third-party annotation
     * tools that don't know `riffle:quads` simply ignore it.
     */
    private fun buildPdfFragmentSelector(loc: JsonObject): JsonObject {
        val locations = loc["locations"] as? JsonObject
        val page = locations?.get("position")?.jsonPrimitive?.intOrNull ?: 1
        val other = locations?.get("otherLocations") as? JsonObject
        val charStart = other?.get("charStart")?.jsonPrimitive?.intOrNull
        val charEnd = other?.get("charEnd")?.jsonPrimitive?.intOrNull
        val quads = other?.get("quads") as? JsonArray
        return buildJsonObject {
            put("type", "FragmentSelector")
            put("conformsTo", PDF_FRAGMENT_CONFORMS_TO)
            put("value", "page=$page")
            if (charStart != null && charEnd != null) {
                put("refinedBy", buildJsonObject {
                    put("type", "DataPositionSelector")
                    put("start", JsonPrimitive(charStart))
                    put("end", JsonPrimitive(charEnd))
                    if (quads != null) put("riffle:quads", quads)
                })
            }
        }
    }

    /**
     * Inverse of [buildPdfFragmentSelector]: read a PDF FragmentSelector and
     * its RefinedBy DataPositionSelector, return the corresponding Readium
     * Locator JSON string (the same shape callers will hand to
     * `Locator.fromJSON` later). Tolerant: missing char range / quads results
     * in a valid bookmark-shaped locator (no `otherLocations`).
     */
    private fun reassemblePdfLocator(
        fragmentSelector: JsonObject,
        chapterHref: String,
    ): String {
        val value = fragmentSelector["value"]?.jsonPrimitive?.content ?: ""
        // `page=N` — bare integer follows the equals sign.
        val page = value.removePrefix("page=").toIntOrNull() ?: 1
        val refinedBy = fragmentSelector["refinedBy"] as? JsonObject
        val charStart = refinedBy?.get("start")?.jsonPrimitive?.intOrNull
        val charEnd = refinedBy?.get("end")?.jsonPrimitive?.intOrNull
        val quads = refinedBy?.get("riffle:quads") as? JsonArray

        val locator = buildJsonObject {
            put("href", chapterHref)
            put("type", "application/pdf")
            put("locations", buildJsonObject {
                put("position", JsonPrimitive(page))
                if (charStart != null && charEnd != null) {
                    put("otherLocations", buildJsonObject {
                        put("charStart", JsonPrimitive(charStart))
                        put("charEnd", JsonPrimitive(charEnd))
                        if (quads != null) put("quads", quads as JsonElement)
                    })
                }
            })
        }
        return locator.toString()
    }
}
