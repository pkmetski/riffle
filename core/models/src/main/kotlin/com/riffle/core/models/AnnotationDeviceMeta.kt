package com.riffle.core.models

import kotlinx.serialization.Serializable

/**
 * Per-device metadata sentinel — the single source of truth for a device's identity and last
 * sync time, regardless of how many annotation files it owns.
 *
 * Stored at `<namespace>/device-meta-<deviceId>.json` (one file per device per namespace) and
 * rewritten by every successful pass through the live controller (push, pull-merge, or
 * solo-PROPFIND). The push-only [AnnotationSweep] additionally writes a sentinel for every
 * namespace it touched on a cycle, but skips the sentinel write when nothing was dirty —
 * pull-only sweeps don't refresh the sentinel because they don't represent a sync event the
 * controller hasn't already advertised. This is what other devices read to answer "is this
 * peer still syncing?"
 *
 * Split from the per-file annotation header so that:
 *  - the per-file header can shrink to `deviceId + bookTitle` (book-scoped) — no duplicated
 *    device label across every annotation file;
 *  - device-label renames cost a single PUT instead of N;
 *  - `lastSyncedAt` advances on pull-only cycles (the per-file header only ever advanced on push,
 *    leaving silent pull-only devices looking dormant to peers).
 *
 * @property deviceId Same UUID embedded in `annotations-<deviceId>.jsonld`.
 * @property label Human-friendly device name. Source order matches the per-file header: user
 *     override > Settings.Global.DEVICE_NAME (API 25+) > `Build.MANUFACTURER + Build.MODEL`.
 * @property lastSyncedAt ISO 8601 timestamp of the last successful sync cycle, push or pull-only.
 *     Drives the "Last synced …" hint on the Maintenance list for foreign devices.
 * @property username The ABS login that owned this device's last cycle. Lets Maintenance label
 *     foreign-user groups by a recognisable name instead of the opaque user id. Null when the
 *     device didn't have a credentialed server when it last synced.
 */
@Serializable
data class AnnotationDeviceMeta(
    val deviceId: String,
    val label: String,
    val lastSyncedAt: String,
    val username: String? = null,
)
