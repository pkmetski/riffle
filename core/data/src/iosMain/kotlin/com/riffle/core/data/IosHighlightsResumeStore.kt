package com.riffle.core.data

import com.riffle.core.domain.HighlightsResumeStore
import platform.Foundation.NSUserDefaults

// NSUserDefaults-backed HighlightsResumeStore for iOS. Keyed by (serverId, itemId) — same
// semantics as the Android DataStore-backed implementation.
internal class IosHighlightsResumeStore : HighlightsResumeStore {

    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun lastHighlightId(serverId: String, itemId: String): String? =
        defaults.stringForKey(keyFor(serverId, itemId))

    override suspend fun setLastHighlightId(serverId: String, itemId: String, annotationId: String) {
        defaults.setObject(annotationId, forKey = keyFor(serverId, itemId))
    }

    private fun keyFor(serverId: String, itemId: String) = "highlights_resume_${serverId}_$itemId"
}
