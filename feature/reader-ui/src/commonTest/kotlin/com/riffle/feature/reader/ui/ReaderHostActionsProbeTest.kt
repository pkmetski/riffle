package com.riffle.feature.reader.ui

import com.riffle.feature.designsystem.HostWiringProbe
import com.riffle.feature.designsystem.NoOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderHostActionsProbeTest {

    private fun fullyWiredActions() = ReaderHostActions(
        onBack = {},
        onSwitchToAudiobook = {},
        onOpenInBook = { _ -> },
    )

    @Test
    fun fullyWiredReaderHostActionsPassProbe() {
        assertTrue(HostWiringProbe.check(fullyWiredActions().probeFields()).isEmpty())
    }

    @Test
    fun readerHostActionsDetectsBackHandledByIosHost() {
        val actions = fullyWiredActions().copy(onBack = NoOp.backHandledByIosHost)
        assertEquals(listOf("onBack"), HostWiringProbe.check(actions.probeFields()))
    }

    @Test
    fun readerHostActionsAllowlistsBackHandledByIosHost() {
        val actions = fullyWiredActions().copy(onBack = NoOp.backHandledByIosHost)
        assertTrue(HostWiringProbe.check(actions.probeFields(), allowlist = setOf("onBack")).isEmpty())
    }

    @Test
    fun readerHostActionsWithOnlyRequiredFieldPassesProbe() {
        val actions = ReaderHostActions(onBack = {})
        assertTrue(HostWiringProbe.check(actions.probeFields()).isEmpty())
    }
}
