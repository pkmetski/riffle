package com.riffle.core.domain

import kotlinx.serialization.Serializable

/**
 * Per-device metadata embedded as a header object at the top of each annotation file.
 *
 * Each per-device annotation file is a JSON array whose first element is a [DeviceMetadata]
 * object — `{type: "riffle:DeviceMeta", deviceId, label, model, lastSeenAt}` — followed by the
 * W3C annotation records. The existing parser drops non-annotation entries (records with no
 * `id`), so the header is invisible to merge and older readers still parse correctly.
 *
 * Previously this metadata lived in a separate `<namespace>__device-<deviceId>.json` sidecar
 * file; that file has been removed. Embedding is atomic with the annotation push, keeps a
 * rename in sync with the annotations, and removes the extra file on the server.
 *
 * @property deviceId The device's UUID — same identifier embedded in `annotations-<deviceId>.jsonld`.
 * @property label Human-friendly name shown to the user. Source order: user override on this
 *     device > `Settings.Global.DEVICE_NAME` (API 25+) > `Build.MANUFACTURER + " " + Build.MODEL`
 *     (manufacturer dropped when MODEL already starts with it). Always non-blank on a real device.
 * @property model Always `Build.MANUFACTURER + " " + Build.MODEL` (manufacturer-dedup applied),
 *     regardless of [label], so two devices sharing a user-overridden label still have a
 *     discriminator in the meta line.
 * @property lastSeenAt ISO 8601 timestamp of the publishing push.
 */
@Serializable
data class DeviceMetadata(
    val deviceId: String,
    val label: String,
    val model: String,
    val lastSeenAt: String,
)
