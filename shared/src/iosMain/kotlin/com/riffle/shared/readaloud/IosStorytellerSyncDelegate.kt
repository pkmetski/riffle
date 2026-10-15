package com.riffle.shared.readaloud

import com.riffle.feature.reader.ReadaloudServerSyncOutcome
import com.riffle.feature.reader.StorytellerSyncDelegate

/** iOS does not run Storyteller-only position sync — always reports offline. */
internal object IosStorytellerSyncDelegate : StorytellerSyncDelegate {
    override suspend fun runCycle(itemId: String, locatorJson: String): ReadaloudServerSyncOutcome =
        ReadaloudServerSyncOutcome.Offline
}
