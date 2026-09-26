package com.riffle.shared.sync

import com.riffle.core.domain.AnnotationSyncConfig
import com.riffle.core.domain.AnnotationSyncConfigStore
import com.riffle.core.domain.AnnotationSyncTarget
import com.riffle.core.sources.webdav.WebDavAnnotationSyncTargetFactory

/**
 * The iOS stand-in for Android's `AnnotationSyncTargetHolder` (#1101): resolves the active
 * [AnnotationSyncTarget] from the saved WebDAV config. Only the WebDAV transport exists on iOS —
 * the ABS-bookmark child Android composes in (ADR 0057) depends on JVM gzip/SHA-256 codecs that
 * have no Kotlin/Native port yet, so ABS-namespaced books route to WebDAV here as every other
 * namespace does.
 *
 * The target is rebuilt only when the config value changes, so repeated sweeps against the same
 * server reuse one Ktor client.
 */
class IosAnnotationSyncTargetProvider(
    private val configStore: AnnotationSyncConfigStore,
    private val webDavFactory: WebDavAnnotationSyncTargetFactory,
) {
    private var lastConfig: AnnotationSyncConfig? = null
    private var lastTarget: AnnotationSyncTarget? = null

    fun current(): AnnotationSyncTarget? {
        val config = configStore.observe().value
        if (config == null) {
            lastConfig = null
            lastTarget = null
            return null
        }
        if (config != lastConfig) {
            lastConfig = config
            lastTarget = webDavFactory.create(config)
        }
        return lastTarget
    }
}
