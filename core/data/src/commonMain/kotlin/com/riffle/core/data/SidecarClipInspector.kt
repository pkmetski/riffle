package com.riffle.core.data

// True when a sidecar carries sentence-level Media Overlay clips (ADR 0040); a clipless one is
// unplayable and gets purged. Implementations keep-on-read-error (return true) so a parse failure doesn't delete a good file.
fun interface SidecarClipInspector {
    fun hasClips(sidecarBytes: ByteArray): Boolean
}
