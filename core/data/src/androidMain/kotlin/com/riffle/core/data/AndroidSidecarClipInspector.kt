package com.riffle.core.data

import java.io.File

/**
 * Android [SidecarClipInspector]: parses the sidecar's Media Overlay timeline via Readium
 * ([MediaOverlayReader]) and reports whether it carries any `<par>` clips. Keeps-on-read-error
 * (returns true) — a parse failure is not ours to diagnose.
 */
object AndroidSidecarClipInspector : SidecarClipInspector {
    override fun hasClips(sidecarBytes: ByteArray): Boolean = runCatching {
        val tmp = File.createTempFile("sidecar-clip-check", ".epub")
        try {
            tmp.writeBytes(sidecarBytes)
            MediaOverlayReader.readTrack(tmp).clips.isNotEmpty()
        } finally {
            tmp.delete()
        }
    }.getOrDefault(true)
}
