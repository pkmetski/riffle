package com.riffle.core.models

import kotlinx.serialization.Serializable

/**
 * Header object embedded at the top of each annotation file.
 *
 * Each per-device annotation file is a JSON array whose first element is an
 * [AnnotationFileHeader] — `{type: "riffle:FileHeader", deviceId, bookTitle}` — followed by the
 * W3C annotation records. The annotation parser drops non-annotation entries (records with no
 * `id`), so the header is invisible to merge and older readers still parse correctly.
 *
 * Device-scoped metadata (label, lastSyncedAt, username) lives in the per-device sentinel
 * [AnnotationDeviceMeta] instead — see its kdoc for the rationale. The per-file header is now
 * book-scoped only.
 *
 * @property deviceId The device's UUID — same identifier embedded in `annotations-<deviceId>.jsonld`.
 * @property bookTitle The local catalog's title for the book this file holds annotations for.
 *     Per-file, refreshed on every push, so re-tagged metadata propagates without explicit
 *     migration. Used on Maintenance to show "Project Hail Mary, Dune, …" instead of opaque
 *     itemIds. Null when the catalog hasn't cached the title yet, or on legacy files.
 */
@Serializable
data class AnnotationFileHeader(
    val deviceId: String,
    val bookTitle: String? = null,
)
