package com.riffle.core.data

/**
 * iOS [SidecarClipInspector]: parses the sidecar's Media Overlay timeline via
 * [IosMediaOverlayReader] and reports whether it carries any `<par>` clips. Keeps-on-read-error
 * (returns true) — a parse failure is not ours to diagnose.
 */
object IosSidecarClipInspector : SidecarClipInspector {
    override fun hasClips(sidecarBytes: ByteArray): Boolean = runCatching {
        IosMediaOverlayReader.readTrack(sidecarBytes).clips.isNotEmpty()
    }.getOrDefault(true)
}
