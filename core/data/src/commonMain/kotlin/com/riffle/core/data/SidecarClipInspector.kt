package com.riffle.core.data

/**
 * Detects whether a cached sidecar carries sentence-level Media Overlay clips (ADR 0040). A sidecar
 * with SMIL structure but no `<par>` clips was fetched during an intermediate Storyteller alignment
 * stage and is unplayable, so [ReadaloudSidecarStore] purges it on init and forces a fresh fetch.
 *
 * Implementations keep-on-read-error (return true) — a parse failure is not ours to diagnose and the
 * file is left in place.
 */
fun interface SidecarClipInspector {
    fun hasClips(sidecarBytes: ByteArray): Boolean
}
