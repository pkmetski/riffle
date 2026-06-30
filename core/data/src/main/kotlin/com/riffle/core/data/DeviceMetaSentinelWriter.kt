package com.riffle.core.data

import com.riffle.core.domain.AnnotationDeviceMeta
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.ServerRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Best-effort writer of this device's per-namespace `AnnotationDeviceMeta` sentinel (#321). The
 * sentinel announces "this device just synced under `<namespace>`" so peer devices see a fresh
 * "Last synced …" timestamp on their Maintenance screen. Failure is swallowed — the next cycle
 * rewrites, and a transient WebDAV blip on the sentinel must not flip a successful merge/push to
 * Failed.
 *
 * Extracted from the duplicated `writeDeviceMetaQuietly` private methods that previously lived on
 * both [AnnotationSyncController] and [AnnotationSweep].
 */
@Singleton
class DeviceMetaSentinelWriter(
    private val deviceIdStore: DeviceIdStore,
    private val deviceLabelResolver: DeviceLabelResolver,
    private val usernameProvider: suspend (serverId: String) -> String?,
    private val nowIso: () -> String = { Instant.now().toString() },
) {
    /**
     * Hilt-injected constructor: resolves username through [ServerRepository.getById]. Tests use
     * the primary constructor with a deterministic `usernameProvider` lambda.
     */
    @Inject
    constructor(
        deviceIdStore: DeviceIdStore,
        deviceLabelResolver: DeviceLabelResolver,
        serverRepository: ServerRepository,
    ) : this(
        deviceIdStore,
        deviceLabelResolver,
        { sid -> serverRepository.getById(sid)?.username },
    )

    /** Write the sentinel for `(serverId, namespace)`. Errors are silently swallowed. */
    suspend fun writeQuietly(target: AnnotationSyncTarget, namespace: String, serverId: String) {
        try {
            val deviceId = deviceIdStore.getOrCreate()
            val body = AnnotationDeviceMetaCodec.encode(
                AnnotationDeviceMeta(
                    deviceId = deviceId,
                    label = deviceLabelResolver.resolveLabel(deviceId),
                    lastSyncedAt = nowIso(),
                    username = usernameProvider(serverId),
                )
            )
            target.writeDeviceMeta(namespace, deviceId, body)
        } catch (_: Exception) {
            // Best-effort. See class kdoc.
        }
    }
}
