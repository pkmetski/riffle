package com.riffle.app.feature.reader.session

import com.riffle.core.data.StorytellerPositionSyncController
import com.riffle.core.data.StorytellerSyncOutcome
import com.riffle.feature.reader.ReadaloudServerSyncOutcome
import com.riffle.feature.reader.StorytellerSyncDelegate

class AndroidStorytellerSyncDelegate(
    private val syncController: StorytellerPositionSyncController,
) : StorytellerSyncDelegate {
    override suspend fun runCycle(itemId: String, locatorJson: String): ReadaloudServerSyncOutcome =
        when (val outcome = syncController.runCycle(itemId, locatorJson)) {
            is StorytellerSyncOutcome.PulledRemote -> ReadaloudServerSyncOutcome.PulledRemote(outcome.locatorJson)
            StorytellerSyncOutcome.PushedLocal -> ReadaloudServerSyncOutcome.PushedLocal
            StorytellerSyncOutcome.InSync -> ReadaloudServerSyncOutcome.InSync
            StorytellerSyncOutcome.Offline -> ReadaloudServerSyncOutcome.Offline
        }
}
