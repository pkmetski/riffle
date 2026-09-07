package com.riffle.core.catalog.oreilly

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the `learning.oreilly.com` Learning API. Field sets are the reverse-engineered surface
 * used by the scrape-and-synthesize path (mirrors the well-known `safaribooks` endpoints); every
 * DTO uses `ignoreUnknownKeys = true` (see [OReillyJson]) so additive server changes don't break
 * parsing. Only the fields Riffle consumes are declared.
 */

@Serializable
internal data class OReillySearchResponse(
    val total: Int = 0,
    val results: List<OReillySearchHit> = emptyList(),
    // Full URL to the next page, or null on the last page. Verified against the live
    // /api/v2/search/ response (2026-09).
    val next: String? = null,
)

@Serializable
internal data class OReillySearchHit(
    // Verified field set against the live /api/v2/search/ response (2026-09). `archive_id` is the
    // id the /api/v1/book/{id}/ detail endpoint accepts (audiobooks carry an "AU" suffix, e.g.
    // "9781617299605AU"). `has_audio` is NOT emitted (always absent) — audio is detected from
    // `format`/`content_format == "audiobook"`. `duration_seconds` is -1 for books.
    @SerialName("archive_id") val archiveId: String? = null,
    val ourn: String? = null,
    val title: String = "",
    val authors: List<String> = emptyList(),
    @SerialName("cover_url") val coverUrl: String? = null,
    val format: String? = null,
    @SerialName("content_format") val contentFormat: String? = null,
    val description: String? = null,
    val issued: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val publishers: List<String> = emptyList(),
    @SerialName("virtual_pages") val virtualPages: Int = 0,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
) {
    /** Best available stable id, preferring the detail-endpoint-accepted `archive_id`. */
    fun bestId(): String? = archiveId ?: ourn

    val isAudiobook: Boolean
        get() = format?.equals("audiobook", ignoreCase = true) == true ||
            contentFormat?.equals("audiobook", ignoreCase = true) == true
}

/**
 * `/api/v2/epubs/{urn}/` metadata (verified live 2026-09). Note: **no `authors`, `cover`, or
 * `publisher`** — those come from the search listing; the detail path builds the cover URL from the
 * id. `descriptions` is a media-type→string map (`text/html`, `text/plain`).
 * `total_running_time_secs` is non-null for audiobooks.
 */
@Serializable
internal data class OReillyBookDetail(
    val identifier: String = "",
    val title: String = "",
    val language: String? = null,
    val isbn: String? = null,
    @SerialName("content_format") val contentFormat: String? = null,
    @SerialName("publication_date") val publicationDate: String? = null,
    @SerialName("virtual_pages") val virtualPages: Int = 0,
    @SerialName("page_count") val pageCount: Int = 0,
    @SerialName("total_running_time_secs") val totalRunningTimeSecs: Double? = null,
    val descriptions: Map<String, String> = emptyMap(),
) {
    val descriptionHtml: String? get() = descriptions["text/html"] ?: descriptions["text/plain"]
    val isAudiobook: Boolean get() = contentFormat?.equals("audiobook", ignoreCase = true) == true
}

/** `/api/v2/epubs/{urn}/spine/` — ordered chapter list with titles. */
@Serializable
internal data class OReillySpineResponse(
    val count: Int = 0,
    val next: String? = null,
    val results: List<OReillySpineItem> = emptyList(),
)

@Serializable
internal data class OReillySpineItem(
    val title: String? = null,
    // e.g. "9781098122584-/xhtml/cover.xhtml" — the packaged full_path is after "-/".
    @SerialName("reference_id") val referenceId: String? = null,
    val ourn: String? = null,
) {
    /** The packaged file path (e.g. "xhtml/cover.xhtml") this spine entry points at. */
    val fullPath: String?
        get() = referenceId?.substringAfter("-/", missingDelimiterValue = "")?.ifBlank { null }
}

/** `/api/v2/epubs/{urn}/files/` — every packaged file (chapters, images, css, fonts). */
@Serializable
internal data class OReillyFilesResponse(
    val count: Int = 0,
    val next: String? = null,
    val results: List<OReillyFileMeta> = emptyList(),
)

@Serializable
internal data class OReillyFileMeta(
    @SerialName("full_path") val fullPath: String = "",
    @SerialName("media_type") val mediaType: String = "application/octet-stream",
    // "chapter" | "image" | "stylesheet" | "font" | "other_asset"
    val kind: String? = null,
    // Real byte size of the file on O'Reilly. Used to detect a throttled truncation: a fetched
    // chapter far smaller than this is the ~2KB DRM sample, not the real content.
    @SerialName("file_size") val fileSize: Long = 0,
)

// ---- Audiobook (Kaltura) surface ------------------------------------------------------------
// O'Reilly audiobooks are Kaltura-hosted "videos" (verified live 2026-09). Playback chain:
// videotocs (chapter list) → videoclips (per-clip kaltura_entry_id) → player/kaltura_config
// (partner_id) + player/kaltura_session (KS) → Kaltura playManifest HLS URL.

/** `/api/v1/videotocs/{id}/` — ordered chapter list for an audiobook (keyed by the bare id). */
@Serializable
internal data class OReillyVideoTocResponse(
    val identifier: String = "",
    val toc: List<OReillyVideoTocEntry> = emptyList(),
)

@Serializable
internal data class OReillyVideoTocEntry(
    // e.g. "9781663721174-a00001" — the key the videoclips endpoint accepts.
    @SerialName("reference_id") val referenceId: String = "",
    val linkid: String? = null,
    val title: String = "",
    // Chapter length in whole seconds.
    val duration: Int = 0,
)

/** `/api/v1/videoclips/{reference_id}/` — resolves a chapter to its Kaltura entry. */
@Serializable
internal data class OReillyVideoClip(
    @SerialName("reference_id") val referenceId: String = "",
    @SerialName("kaltura_entry_id") val kalturaEntryId: String = "",
    val duration: Int = 0,
    val title: String = "",
)

/** `/api/v1/player/kaltura_config/` — the Kaltura partner the account streams under. */
@Serializable
internal data class OReillyKalturaConfig(
    @SerialName("partner_id") val partnerId: String = "",
)

/** `/api/v1/player/kaltura_session/` — a short-lived (~1h) Kaltura session token. */
@Serializable
internal data class OReillyKalturaSession(
    val session: String = "",
    val expiry: String? = null,
)
