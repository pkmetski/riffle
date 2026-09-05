package com.riffle.core.catalog.gutenberg

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure functions that decode Gutendex JSON responses into module-internal DTOs.
 *
 * Gutendex is at https://gutendex.com — a JSON mirror of the Project Gutenberg catalogue. The
 * listing response shape is `{ count, next, previous, results: [ { … } ] }`; each result carries
 * `formats: { <mime>: <url> }` from which we pick the EPUB. We stay tolerant of missing fields
 * (the mirror occasionally omits `description` or ships books without an `application/epub+zip`
 * entry) — those items still surface in the grid but the EPUB link is null so the download
 * action is disabled.
 */
internal object GutenbergParser {

    const val BASE: String = "https://gutendex.com"
    const val GUTENBERG_BASE: String = "https://www.gutenberg.org"

    // Tolerate future Gutendex fields (`copyright`, `media_type`, …) without failing the parse —
    // Riffle only reads a subset of the schema.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseListing(body: String): GutenbergListingResult {
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonArray ?: JsonArray(emptyList())
        val items = results.mapNotNull { parseSummary(it) }
        val next = root["next"]?.let { if (it is JsonPrimitive) it.contentOrNull else null }
        val count = root["count"]?.jsonPrimitive?.intOrNull ?: items.size
        return GutenbergListingResult(items = items, next = next, count = count)
    }

    fun parseBook(body: String): GutenbergBookSummary? {
        val root = json.parseToJsonElement(body).jsonObject
        // /books/{id} returns the book object directly. Some Gutendex mirrors wrap it in
        // `{ results: [ … ] }` when queried via /books/?ids=X; handle both shapes.
        val obj = root["results"]?.jsonArray?.firstOrNull()?.jsonObject ?: root
        return parseSummary(obj)
    }

    private fun parseSummary(element: JsonElement): GutenbergBookSummary? {
        val obj = element as? JsonObject ?: return null
        val id = obj["id"]?.jsonPrimitive?.longOrNull ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val authors = obj["authors"]?.jsonArray.orEmptyArray()
            .mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
        val languages = obj["languages"]?.jsonArray.orEmptyArray()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val subjects = obj["subjects"]?.jsonArray.orEmptyArray()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val bookshelves = obj["bookshelves"]?.jsonArray.orEmptyArray()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val formats = obj["formats"] as? JsonObject
        val epubUrl = formats?.let { pickEpubUrl(it) }
        val coverUrl = formats?.let { pickCoverUrl(it) }
        val description = obj["summaries"]?.jsonArray.orEmptyArray()
            .firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull }
        return GutenbergBookSummary(
            id = id,
            title = title,
            authors = authors,
            languages = languages,
            subjects = subjects,
            bookshelves = bookshelves,
            coverUrl = coverUrl,
            epubUrl = epubUrl,
            description = description?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Pick the EPUB URL from the Gutendex `formats` map. Preference order:
     *  1. `application/epub+zip` (canonical MIME, keyed by mirror behaviour to point at
     *     `/ebooks/{id}.epub3.images` — the modern EPUB with a cover).
     *  2. Any key that starts with `application/epub+zip` (Gutendex sometimes appends
     *     `; charset=utf-8` to distinguish encoded variants).
     */
    private fun pickEpubUrl(formats: JsonObject): String? {
        val exact = formats["application/epub+zip"] as? JsonPrimitive
        if (exact != null) return exact.contentOrNull
        val prefixed = formats.entries.firstOrNull { (k, _) ->
            k.startsWith("application/epub+zip")
        }?.value as? JsonPrimitive
        return prefixed?.contentOrNull
    }

    /**
     * Pick a cover URL from the `formats` map. Gutendex serves the cover as `image/jpeg` at
     * `/cache/epub/{id}/pg{id}.cover.medium.jpg`. Falls through gracefully when the mirror
     * doesn't ship one.
     */
    private fun pickCoverUrl(formats: JsonObject): String? {
        val jpeg = formats.entries.firstOrNull { (k, _) ->
            k.startsWith("image/jpeg") || k.startsWith("image/png")
        }?.value as? JsonPrimitive
        return jpeg?.contentOrNull
    }

    private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())
}
