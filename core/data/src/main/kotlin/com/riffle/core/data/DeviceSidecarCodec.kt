package com.riffle.core.data

import com.riffle.core.domain.DeviceSidecar
import kotlinx.serialization.json.Json

/** JSON codec for [DeviceSidecar] payloads written under `<namespace>__device-<deviceId>.json`. */
object DeviceSidecarCodec {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun encode(sidecar: DeviceSidecar): String = json.encodeToString(DeviceSidecar.serializer(), sidecar)

    /** Returns null on malformed input — peers may publish a future schema we don't recognise. */
    fun decode(payload: String): DeviceSidecar? = try {
        json.decodeFromString(DeviceSidecar.serializer(), payload)
    } catch (_: Exception) {
        null
    }
}
