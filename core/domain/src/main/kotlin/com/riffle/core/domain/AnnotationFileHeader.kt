package com.riffle.core.domain

import kotlinx.serialization.Serializable

/**
 * Header object embedded at the top of each annotation file.
 *
 * Each per-device annotation file is a JSON array whose first element is an
 * [AnnotationFileHeader] — `{type: "riffle:FileHeader", deviceId, label, lastSeenAt, username}`
 * — followed by the W3C annotation records. The annotation parser drops non-annotation
 * entries (records with no `id`), so the header is invisible to merge and older readers still
 * parse correctly.
 *
 * The name reflects what the object actually is — the header of a per-device annotation file —
 * rather than calling it "device metadata", because it carries account context too, not just
 * device fields.
 *
 * @property deviceId The device's UUID — same identifier embedded in `annotations-<deviceId>.jsonld`.
 * @property label Human-friendly device name shown to the user. Source order: user override on
 *     this device > `Settings.Global.DEVICE_NAME` (API 25+) > `Build.MANUFACTURER + " " + Build.MODEL`
 *     (manufacturer dropped when MODEL already starts with it). Always non-blank on a real device.
 * @property lastSeenAt ISO 8601 timestamp of the publishing push. Drives the "Last synced …"
 *     hint in the Maintenance list so the user can pick the dead device to Forget.
 * @property username The account username that owned this push (i.e. the login used by the
 *     writing device). Read on Maintenance to label a *foreign* user's group of files — the
 *     namespace itself is the opaque user id, which gives the user nothing to recognise.
 *     Null on legacy files written before the field existed; renderers fall back to the id.
 * @property bookTitle The local catalog's title for the book this file holds annotations for.
 *     Per-file, refreshed on every push, so re-tagged metadata propagates without explicit
 *     migration. Used on Maintenance to show "Project Hail Mary, Dune, …" instead of opaque
 *     itemIds. Null when the catalog hasn't cached the title yet, or on legacy files.
 */
@Serializable
data class AnnotationFileHeader(
    val deviceId: String,
    val label: String,
    val lastSeenAt: String,
    val username: String? = null,
    val bookTitle: String? = null,
)
