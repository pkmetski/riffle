package com.riffle.core.domain

/**
 * Resolves this device's display label following the documented fallback chain:
 *
 * 1. User override from [DeviceLabelStore] (set on the Maintenance screen).
 * 2. OS device name — `Settings.Global.DEVICE_NAME` (API 25+ only).
 * 3. `Build.MANUFACTURER + " " + Build.MODEL`, with manufacturer dropped when MODEL already
 *    starts with it (so "Google" + "Pixel 9 Pro" becomes "Pixel 9 Pro", not "Google Pixel 9 Pro
 *    Google").
 * 4. `device-${deviceId.take(8)}` last-resort.
 *
 * The Android wiring sits in `core/data/AndroidDeviceLabelResolver` so the domain module
 * stays JVM-clean and unit-testable.
 */
interface DeviceLabelResolver {
    /** The label to publish for [deviceId]; never blank. */
    suspend fun resolveLabel(deviceId: String): String

    /** The Build-identifier `model` string published in the sidecar; never blank. */
    fun deviceModel(): String
}
