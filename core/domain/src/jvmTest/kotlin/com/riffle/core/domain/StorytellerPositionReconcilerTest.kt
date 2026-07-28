package com.riffle.core.domain

import com.riffle.core.domain.StorytellerPositionReconciler.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class StorytellerPositionReconcilerTest {

    @Test fun `remote newer pulls the remote position`() {
        val d = StorytellerPositionReconciler.reconcile(
            localLocatorJson = "L", localUpdatedAt = 1_000,
            remoteLocatorJson = "R", remoteUpdatedAt = 2_000,
        )
        assertEquals(Decision.PullRemote("R", 2_000), d)
    }

    @Test fun `local newer pushes the local position`() {
        val d = StorytellerPositionReconciler.reconcile(
            localLocatorJson = "L", localUpdatedAt = 3_000,
            remoteLocatorJson = "R", remoteUpdatedAt = 1_000,
        )
        assertEquals(Decision.PushLocal("L", 3_000), d)
    }

    @Test fun `equal timestamps are in sync`() {
        val d = StorytellerPositionReconciler.reconcile("L", 2_000, "R", 2_000)
        assertEquals(Decision.InSync, d)
    }

    @Test fun `no remote record but local progress pushes local`() {
        val d = StorytellerPositionReconciler.reconcile(
            localLocatorJson = "L", localUpdatedAt = 5_000,
            remoteLocatorJson = null, remoteUpdatedAt = 0,
        )
        assertEquals(Decision.PushLocal("L", 5_000), d)
    }

    @Test fun `no remote record and no local progress is in sync`() {
        val d = StorytellerPositionReconciler.reconcile("", 0, null, 0)
        assertEquals(Decision.InSync, d)
    }
}
