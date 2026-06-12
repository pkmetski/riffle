package com.riffle.core.domain

import com.riffle.core.domain.AudiobookPositionReconciler.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookPositionReconcilerTest {

    @Test
    fun `remote newer than local pulls the remote position`() {
        val d = AudiobookPositionReconciler.reconcile(
            localSec = 10.0, localUpdatedAt = 100L, remoteSec = 50.0, remoteUpdatedAt = 200L,
        )
        assertEquals(Decision.PullRemote(50.0, 200L), d)
    }

    @Test
    fun `local newer than remote pushes the local position`() {
        val d = AudiobookPositionReconciler.reconcile(
            localSec = 80.0, localUpdatedAt = 300L, remoteSec = 50.0, remoteUpdatedAt = 200L,
        )
        assertEquals(Decision.PushLocal(80.0, 300L), d)
    }

    @Test
    fun `equal timestamps are in sync`() {
        val d = AudiobookPositionReconciler.reconcile(
            localSec = 80.0, localUpdatedAt = 200L, remoteSec = 50.0, remoteUpdatedAt = 200L,
        )
        assertEquals(Decision.InSync, d)
    }

    @Test
    fun `no local row with a server stamp pulls the remote`() {
        val d = AudiobookPositionReconciler.reconcile(
            localSec = null, localUpdatedAt = 0L, remoteSec = 50.0, remoteUpdatedAt = 200L,
        )
        assertEquals(Decision.PullRemote(50.0, 200L), d)
    }

    @Test
    fun `no local row and no server stamp is in sync`() {
        val d = AudiobookPositionReconciler.reconcile(
            localSec = null, localUpdatedAt = 0L, remoteSec = 0.0, remoteUpdatedAt = 0L,
        )
        assertEquals(Decision.InSync, d)
    }

    @Test
    fun `a local stamp of zero never pushes even if remote is also zero`() {
        val d = AudiobookPositionReconciler.reconcile(
            localSec = 80.0, localUpdatedAt = 0L, remoteSec = 0.0, remoteUpdatedAt = 0L,
        )
        assertEquals(Decision.InSync, d)
    }
}
