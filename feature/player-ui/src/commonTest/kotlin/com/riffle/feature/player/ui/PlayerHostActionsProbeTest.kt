package com.riffle.feature.player.ui

import com.riffle.feature.designsystem.HostWiringProbe
import com.riffle.feature.designsystem.NoOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerHostActionsProbeTest {

    private fun fullyWiredActions() = PlayerHostActions(
        onBack = {},
        onPlaylistAdvance = { _, _ -> },
        onSwitchToReadaloud = { _, _ -> },
    )

    @Test
    fun fullyWiredPlayerHostActionsPassProbe() {
        assertTrue(HostWiringProbe.check(fullyWiredActions().probeFields()).isEmpty())
    }

    @Test
    fun playerHostActionsDetectsPlaylistAdvanceNoOp() {
        val actions = fullyWiredActions().copy(onPlaylistAdvance = NoOp.playlistAdvanceUnavailable)
        assertEquals(listOf("onPlaylistAdvance"), HostWiringProbe.check(actions.probeFields()))
    }

    @Test
    fun playerHostActionsAllowlistsPlaylistAdvanceNoOpForBookmarkContext() {
        val actions = fullyWiredActions().copy(onPlaylistAdvance = NoOp.playlistAdvanceUnavailable)
        val allowlist = setOf("onPlaylistAdvance")
        assertTrue(HostWiringProbe.check(actions.probeFields(), allowlist).isEmpty())
    }

    @Test
    fun playerHostActionsDetectsBackHandledByIosHost() {
        val actions = fullyWiredActions().copy(onBack = NoOp.backHandledByIosHost)
        assertEquals(listOf("onBack"), HostWiringProbe.check(actions.probeFields()))
    }

    @Test
    fun playerHostActionsWithoutSwitchToReadaloudPassesProbe() {
        val actions = PlayerHostActions(
            onBack = {},
            onPlaylistAdvance = { _, _ -> },
        )
        assertTrue(HostWiringProbe.check(actions.probeFields()).isEmpty())
    }
}
