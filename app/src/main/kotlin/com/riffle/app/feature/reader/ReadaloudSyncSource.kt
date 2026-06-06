package com.riffle.app.feature.reader

/**
 * The canonical position a three-peer cycle should sync. When readaloud is playing, the audio
 * position is the source of truth (ADR 0019) — so listening advances every peer even when the page
 * can't (backgrounded, or auto-follow hasn't turned the page). Use [audioCanonicalJson] if it
 * resolves; otherwise fall back to the displayed [pageLocatorJson]. Null when neither is available.
 */
fun canonicalForCycle(isPlaying: Boolean, audioCanonicalJson: String?, pageLocatorJson: String?): String? =
    if (isPlaying && audioCanonicalJson != null) audioCanonicalJson else pageLocatorJson
