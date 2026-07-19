package com.riffle.core.data

import com.riffle.core.models.AnnotationDeviceMeta
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.domain.DeviceIdStore
import com.riffle.core.domain.DeviceLabelResolver
import com.riffle.core.domain.SourceRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

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
    private val usernameProvider: suspend (sourceId: String) -> String?,
    private val nowIso: () -> String = { Instant.now().toString() },
) {
    /**
     * Hilt-injected constructor: resolves username through [SourceRepository.getById]. Tests use
     * the primary constructor with a deterministic `usernameProvider` lambda.
     */
    @Inject
    constructor(
        deviceIdStore: DeviceIdStore,
        deviceLabelResolver: DeviceLabelResolver,
        sourceRepository: SourceRepository,
    ) : this(
        deviceIdStore,
        deviceLabelResolver,
        { sid -> sourceRepository.getById(sid)?.username },
    )

    /** Write the sentinel for `(sourceId, namespace)`. Errors are silently swallowed. */
    suspend fun writeQuietly(target: AnnotationSyncTarget, namespace: String, sourceId: String) {
        try {
            val deviceId = deviceIdStore.getOrCreate()
            val body = AnnotationDeviceMetaCodec.encode(
                AnnotationDeviceMeta(
                    deviceId = deviceId,
                    label = deviceLabelResolver.resolveLabel(deviceId),
                    lastSyncedAt = nowIso(),
                    username = usernameProvider(sourceId),
                )
            )
            target.writeDeviceMeta(namespace, deviceId, body)
        } catch (e: CancellationException) {
            // Never swallow cancellation — structured concurrency needs the unwind.
            throw e
        } catch (_: Exception) {
            // Best-effort. See class kdoc.
        }
    }
}
