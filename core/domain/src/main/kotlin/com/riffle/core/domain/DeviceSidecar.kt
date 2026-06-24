package com.riffle.core.domain

import kotlinx.serialization.Serializable

/**
 * Per-device metadata published as a sidecar file alongside annotation files.
 *
 * One file per (namespace, device) at the logical path:
 * ```
 * <namespace>/device-<deviceId>.json
 * ```
 *
 * Refreshed (PUT, best-effort, exceptions swallowed) on every successful annotation push by
 * [com.riffle.core.data.AnnotationSyncController.pushPending]. Consumed by the Maintenance UI
 * to render peer device names and "last seen N days ago" hints in the per-device row list.
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
data class DeviceSidecar(
    val deviceId: String,
    val label: String,
    val model: String,
    val lastSeenAt: String,
)
