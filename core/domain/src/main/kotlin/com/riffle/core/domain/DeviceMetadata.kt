package com.riffle.core.domain

import kotlinx.serialization.Serializable

/**
 * Per-device metadata embedded as a header object at the top of each annotation file.
 *
 * Each per-device annotation file is a JSON array whose first element is a [DeviceMetadata]
 * object — `{type: "riffle:DeviceMeta", deviceId, label, lastSeenAt}` — followed by the W3C
 * annotation records. The existing parser drops non-annotation entries (records with no `id`),
 * so the header is invisible to merge and older readers still parse correctly.
 *
 * @property deviceId The device's UUID — same identifier embedded in `annotations-<deviceId>.jsonld`.
 * @property label Human-friendly name shown to the user. Source order: user override on this
 *     device > `Settings.Global.DEVICE_NAME` (API 25+) > `Build.MANUFACTURER + " " + Build.MODEL`
 *     (manufacturer dropped when MODEL already starts with it). Always non-blank on a real device.
 * @property lastSeenAt ISO 8601 timestamp of the publishing push. Drives the "Last seen …"
 *     hint in the Maintenance list so the user can pick the dead device to Forget.
 */
@Serializable
data class DeviceMetadata(
    val deviceId: String,
    val label: String,
    val lastSeenAt: String,
)
