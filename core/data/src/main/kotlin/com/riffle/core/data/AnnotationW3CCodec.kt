package com.riffle.core.data

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.EmbeddedFigure
import com.riffle.core.domain.W3CAnnotation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

private val embeddedFiguresSerializer = ListSerializer(EmbeddedFigure.serializer())
private val embeddedFiguresJson = Json { ignoreUnknownKeys = true }

/**
 * Parses the [AnnotationEntity.embeddedFigures] JSON column into domain figures, sorted by
 * [EmbeddedFigure.order]. Tolerant of malformed/absent columns (returns empty).
 */
internal fun embeddedFiguresColumnToList(raw: String?): List<EmbeddedFigure> {
    if (raw.isNullOrEmpty()) return emptyList()
    return try {
        embeddedFiguresJson.decodeFromString(embeddedFiguresSerializer, raw).sortedBy { it.order }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Inverse of [embeddedFiguresColumnToList]. Null and empty lists both map to a null column. */
internal fun embeddedFiguresListToColumn(figures: List<EmbeddedFigure>?): String? =
    if (figures.isNullOrEmpty()) null else embeddedFiguresJson.encodeToString(embeddedFiguresSerializer, figures)

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
            // "describing" is a standard Web Annotation motivation (the body describes the
            // target rather than commenting/highlighting it) — a natural fit for a standalone
            // figure annotation. The decoder derives type from body composition regardless
            // (see w3cObjectToAnnotation), so this value isn't load-bearing on round-trip.
            AnnotationEntity.TYPE_IMAGE -> "describing"
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

        // Build the body value: a single JsonObject for the common single-body case, or a
        // JsonArray when a TYPE_HIGHLIGHT carries embedded figures alongside its text body.
        // `riffle:image` bodies are unknown to older consumers, which ignore unrecognised body
        // types/entries, so this degrades gracefully.
        val bodies = mutableListOf<JsonObject>()
        if (entity.type == AnnotationEntity.TYPE_IMAGE) {
            // TYPE_IMAGE carries no TextualBody — just the single riffle:image body.
            bodies += buildRiffleImageBody(
                href = entity.imageHref,
                svg = entity.imageSvg,
                caption = entity.textSnippet,
                order = null,
                imageBytes = entity.imageBytes,
            )
        } else {
            bodies += buildJsonObject {
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
            if (entity.type == AnnotationEntity.TYPE_HIGHLIGHT) {
                val figures = embeddedFiguresColumnToList(entity.embeddedFigures)
                for (figure in figures) {
                    bodies += buildRiffleImageBody(
                        href = figure.href,
                        svg = figure.svg,
                        caption = figure.caption,
                        order = figure.order,
                        imageBytes = figure.imageBytes,
                        charOffset = figure.charOffset,
                    )
                }
            }
        }
        val bodyValue: JsonElement = if (bodies.size == 1) bodies[0] else buildJsonArray { bodies.forEach { add(it) } }

        // Build the main W3C annotation object
        val annotationObject = buildJsonObject {
            put("@context", "http://www.w3.org/ns/anno.jsonld")
            put("id", "urn:uuid:${entity.id}")
            put("type", "Annotation")
            put("motivation", motivation)
            put("target", targetObject)
            put("body", bodyValue)
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
            // Real chapter resource path (e.g. "OPS/chapter-139.xhtml"). `target.source` only
            // encodes the itemId, so without this extension a peer receiving the file cannot
            // recover the chapter path — and the reader's page-bookmarked indicator, which
            // matches on normalized chapterHref, stays dark for cross-device bookmarks.
            put("riffle:chapterHref", entity.chapterHref)
            if (entity.type == AnnotationEntity.TYPE_BOOKMARK && entity.bookmarkTitle.isNotEmpty()) {
                put("riffle:bookmarkTitle", entity.bookmarkTitle)
            }
        }

        return annotationObject.toString()
    }

    /**
     * Builds a single `riffle:image` Web Annotation body. `order` is only meaningful for figures
     * embedded in a TYPE_HIGHLIGHT range (stable sort key); omitted (null) for TYPE_IMAGE.
     */
    private fun buildRiffleImageBody(
        href: String?,
        svg: String?,
        caption: String,
        order: Int?,
        imageBytes: String? = null,
        charOffset: Long? = null,
    ): JsonObject =
        buildJsonObject {
            put("type", "riffle:image")
            put("value", buildJsonObject {
                if (href != null) put("href", href)
                if (svg != null) put("svg", svg)
                put("caption", caption)
                if (order != null) put("order", JsonPrimitive(order))
                // Extension fields (2026-07-14): preserve captured raster bytes and the figure's
                // position-in-range so a sync round-trip doesn't reset local rendering. Older
                // peers ignore unknown value fields, so this stays backward-compatible.
                if (imageBytes != null) put("imageBytes", imageBytes)
                if (charOffset != null) put("charOffset", JsonPrimitive(charOffset))
            })
        }

    /**
     * Parses a single `riffle:image` body's `value` object into an [EmbeddedFigure]. `href` wins
     * over `svg` when both are present (malformed input — the encoder never emits both); a body
     * with neither is skipped (returns null). `fallbackOrder` supplies encounter-order when the
     * body carries no explicit `order` (e.g. a TYPE_IMAGE body, which never emits one).
     */
    private fun parseRiffleImageBody(body: JsonObject, fallbackOrder: Int): EmbeddedFigure? {
        val value = body["value"]?.jsonObject ?: return null
        val href = value["href"]?.jsonPrimitive?.content
        val svg = value["svg"]?.jsonPrimitive?.content
        if (href == null && svg == null) return null
        val caption = value["caption"]?.jsonPrimitive?.content ?: ""
        val order = value["order"]?.jsonPrimitive?.intOrNull ?: fallbackOrder
        // Extension fields (2026-07-14): default null when the body was written by an older
        // peer. See [buildRiffleImageBody] for the outbound half.
        val imageBytes = value["imageBytes"]?.jsonPrimitive?.contentOrNull
        val charOffset = value["charOffset"]?.jsonPrimitive?.longOrNull
        return EmbeddedFigure(
            href = href,
            svg = if (href != null) null else svg,
            caption = caption,
            order = order,
            imageBytes = imageBytes,
            charOffset = charOffset,
        )
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
        embeddedFigures = embeddedFiguresColumnToList(entity.embeddedFigures).ifEmpty { null },
        imageHref = entity.imageHref,
        imageSvg = entity.imageSvg,
        imageBytes = entity.imageBytes,
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

            // Extract motivation and map to type. Overridden below once bodies are inspected:
            // the presence/absence of a text body alongside any `riffle:image` bodies is the
            // authoritative signal for TYPE_IMAGE vs TYPE_HIGHLIGHT (see w3cObjectToAnnotation
            // kdoc on body composition), not the motivation string.
            val motivation = root["motivation"]?.jsonPrimitive?.content ?: ""
            var type = when (motivation) {
                "highlighting" -> AnnotationEntity.TYPE_HIGHLIGHT
                "bookmarking" -> AnnotationEntity.TYPE_BOOKMARK
                else -> AnnotationEntity.TYPE_HIGHLIGHT
            }

            // Real chapter path extension. When present, prefer it over the itemId derived from
            // `target.source` — otherwise a WebDAV round-trip loses the real chapter path (only
            // the itemId is on the wire in `target.source = "epub://item-<itemId>"`), which
            // makes the reader's page-bookmarked indicator go dark. Files predating the
            // extension fall through to the source-derived path; the merge orchestrator
            // preserves any locally-known chapterHref in that case. Read here — before the
            // target selectors run — so `reassemblePdfLocator` also sees the real path.
            val chapterHrefExtension = root["riffle:chapterHref"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotEmpty() }

            // Extract target and selectors
            val target = root["target"]?.jsonObject
            var cfi = ""
            var textSnippet = ""
            var textBefore = ""
            var textAfter = ""
            var chapterHref = ""

            target?.let {
                val src = it["source"]?.jsonPrimitive?.content ?: ""
                chapterHref = chapterHrefExtension
                    ?: src.removePrefix("epub://item-").removePrefix("pdf://item-")
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

            // Extract body fields. `body` is a single JsonObject for the common case, or a
            // JsonArray when a TYPE_HIGHLIGHT carries embedded figures alongside its text body
            // (see annotationEntityToW3C). Split into the text body (anything not
            // `riffle:image`) and the `riffle:image` bodies.
            val bodyElement = root["body"]
            val bodyList: List<JsonObject> = when (bodyElement) {
                is JsonArray -> bodyElement.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(bodyElement)
                else -> emptyList()
            }
            val imageBodies = bodyList.filter { it["type"]?.jsonPrimitive?.content == "riffle:image" }
            val textBody = bodyList.firstOrNull { it["type"]?.jsonPrimitive?.content != "riffle:image" }

            var color: String? = null
            var note: String? = null
            var bookmarkTitle: String? = null

            textBody?.let {
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

            // Resolve TYPE_IMAGE / embedded-figures from body composition (authoritative — see
            // note on `type` above).
            var embeddedFigures: List<EmbeddedFigure>? = null
            var imageHref: String? = null
            var imageSvg: String? = null
            var imageBytes: String? = null
            if (imageBodies.isNotEmpty()) {
                if (textBody != null) {
                    type = AnnotationEntity.TYPE_HIGHLIGHT
                    embeddedFigures = imageBodies
                        .mapIndexedNotNull { index, imgBody -> parseRiffleImageBody(imgBody, fallbackOrder = index) }
                        .sortedBy { it.order }
                        .ifEmpty { null }
                } else {
                    type = AnnotationEntity.TYPE_IMAGE
                    // Defensive: more than one riffle:image body with no text body shouldn't
                    // happen with our own encoder. Take the first silently.
                    val figure = parseRiffleImageBody(imageBodies.first(), fallbackOrder = 0)
                    imageHref = figure?.href
                    imageSvg = figure?.svg
                    imageBytes = figure?.imageBytes
                    if (figure != null) {
                        textSnippet = figure.caption
                    }
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
                embeddedFigures = embeddedFigures,
                imageHref = imageHref,
                imageSvg = imageSvg,
                imageBytes = imageBytes,
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
