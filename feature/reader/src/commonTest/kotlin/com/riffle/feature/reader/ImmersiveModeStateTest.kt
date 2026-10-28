package com.riffle.feature.reader

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImmersiveModeStateTest {

    private class FakeController : SystemBarsController {
        var hideCount = 0
        var showCount = 0
        var applyImmersiveBehaviorCount = 0
        var reapplyRawCount = 0

        override fun hide() { hideCount++ }
        override fun show() { showCount++ }
        override fun applyImmersiveBehavior() { applyImmersiveBehaviorCount++ }
        override fun reapplyRaw() { reapplyRawCount++ }
    }

    private lateinit var controller: FakeController
    private var nowMs = 0L
    private lateinit var state: ImmersiveModeState

    @BeforeTest
    fun setUp() {
        controller = FakeController()
        nowMs = 0L
        state = ImmersiveModeState(controller, clock = { nowMs })
    }

    @Test
    fun initialStateIsNotImmersive() {
        assertFalse(state.isImmersive.value)
        assertFalse(state.systemBarsHiddenForTest)
        assertEquals(0, controller.hideCount)
        assertEquals(0, controller.showCount)
    }

    @Test
    fun hideEntersImmersiveAndHidesSystemBars() {
        state.hide()

        assertTrue(state.isImmersive.value)
        assertTrue(state.systemBarsHiddenForTest)
        assertEquals(1, controller.hideCount)
        assertEquals(1, controller.applyImmersiveBehaviorCount)
    }

    @Test
    fun hideIsIdempotentSoDuplicateCallsDoNotInvokeControllerHideAgain() {
        state.hide()
        state.hide()
        state.hide()

        assertEquals(1, controller.hideCount)
        assertTrue(state.isImmersive.value)
    }

    @Test
    fun hideForceReInvokesControllerHideEvenWhenFlagSaysHidden() {
        state.hide()
        assertEquals(1, controller.hideCount)

        state.hide(force = true)

        assertEquals(2, controller.hideCount)
        assertTrue(state.isImmersive.value)
        assertTrue(state.systemBarsHiddenForTest)
    }

    @Test
    fun toggleFromNonImmersiveEntersImmersive() {
        state.toggle()

        assertTrue(state.isImmersive.value)
        assertEquals(1, controller.hideCount)
    }

    @Test
    fun toggleFromImmersiveExitsImmersiveAndShowsBars() {
        state.hide()
        controller.hideCount = 0

        state.toggle()

        assertFalse(state.isImmersive.value)
        assertFalse(state.systemBarsHiddenForTest)
        assertEquals(1, controller.showCount)
    }

    @Test
    fun toggleNotifiesOnUserImmersiveChangedWithTheNewState() {
        val changes = mutableListOf<Boolean>()
        state.onUserImmersiveChanged = { changes += it }

        state.toggle()
        state.toggle()

        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun dismissOverlayDoesNothingWithinToggleCooldownMsOfConstruction() {
        state.dismissOverlay()

        assertFalse(state.isImmersive.value)
        assertEquals(0, controller.hideCount)
    }

    @Test
    fun dismissOverlayEntersFullImmersiveOnScrollWhenBarsAreVisibleAndCooldownElapsed() {
        nowMs = ImmersiveModeState.TOGGLE_COOLDOWN_MS + 1
        state.dismissOverlay()

        assertTrue(state.isImmersive.value)
        assertTrue(state.systemBarsHiddenForTest)
        assertEquals(1, controller.hideCount)
    }

    @Test
    fun dismissOverlayHidesAppBarWhenBarsAreHiddenAndCooldownElapsed() {
        state.hide()
        nowMs = ImmersiveModeState.TOGGLE_COOLDOWN_MS + 1

        state.setIsImmersiveForTest(false)
        state.dismissOverlay()

        assertTrue(state.isImmersive.value)
    }

    @Test
    fun dismissOverlayIsSuppressedWithinToggleCooldownMsAfterToggle() {
        nowMs = 1_000
        state.toggle()
        nowMs = 2_000
        state.toggle()
        nowMs = 2_100
        state.toggle()

        state.setIsImmersiveForTest(false)
        state.dismissOverlay()

        assertFalse(state.isImmersive.value)
    }

    @Test
    fun onBarsRestoredExternallyReHidesAndStaysImmersiveForAnUnrequestedReveal() {
        state.hide()
        assertTrue(state.systemBarsHiddenForTest)
        controller.hideCount = 0

        state.onBarsRestoredExternally()

        assertTrue(state.isImmersive.value)
        assertTrue(state.systemBarsHiddenForTest)
        assertEquals(1, controller.hideCount)
        assertEquals(0, controller.showCount)
    }

    @Test
    fun onBarsRestoredExternallyFollowsAUserRequestedRevealByExitingImmersive() {
        state.show()
        controller.hideCount = 0

        state.onBarsRestoredExternally()

        assertFalse(state.isImmersive.value)
        assertFalse(state.systemBarsHiddenForTest)
        assertEquals(0, controller.hideCount)
    }

    @Test
    fun showResetsStateAndCallsControllerShow() {
        state.hide()

        state.show()

        assertFalse(state.isImmersive.value)
        assertFalse(state.systemBarsHiddenForTest)
        assertEquals(1, controller.showCount)
    }

    @Test
    fun focusRegainWhileImmersiveCallsReapplyRawToBypassTheCompatCache() {
        state.hide()

        state.onWindowFocusChanged(true)
        state.onWindowFocusChanged(false)
        assertEquals(0, controller.reapplyRawCount)

        state.onWindowFocusChanged(true)
        assertEquals(1, controller.reapplyRawCount)
        assertTrue(state.isImmersive.value)
    }

    @Test
    fun firstFocusEventJustSeedsTheTrackerSoAFocusedAtComposeReaderDoesNotSpuriousReapply() {
        state.hide()
        controller.reapplyRawCount = 0

        state.onWindowFocusChanged(true)

        assertEquals(0, controller.reapplyRawCount)
    }

    @Test
    fun readerComposedUnfocusedThenGainingFocusIsTreatedAsAFocusRegain() {
        state.hide()
        controller.reapplyRawCount = 0

        state.onWindowFocusChanged(false)
        state.onWindowFocusChanged(true)

        assertEquals(1, controller.reapplyRawCount)
        assertTrue(state.isImmersive.value)
    }

    @Test
    fun focusRegainWhenNotImmersiveDoesNotReapplyTheFlags() {
        state.onWindowFocusChanged(false)
        state.onWindowFocusChanged(true)

        assertEquals(0, controller.reapplyRawCount)
        assertFalse(state.isImmersive.value)
    }

    @Test
    fun focusLossAloneDoesNotTouchTheController() {
        state.hide()

        state.onWindowFocusChanged(false)

        assertEquals(0, controller.reapplyRawCount)
    }

    @Test
    fun initialIsImmersiveTrueSeedsIsImmersiveWithoutCallingController() {
        val freshController = FakeController()
        val seeded = ImmersiveModeState(freshController, clock = { nowMs }, initialIsImmersive = true)

        assertTrue(seeded.isImmersive.value)
        assertFalse(seeded.systemBarsHiddenForTest)
        assertEquals(0, freshController.hideCount)
    }

    @Test
    fun onBarsRestoredExternallyInitializationRaceReHidesWhenIsImmersiveButSystemBarsHiddenFalse() {
        val freshController = FakeController()
        val seeded = ImmersiveModeState(freshController, clock = { nowMs }, initialIsImmersive = true)
        assertFalse(seeded.systemBarsHiddenForTest)

        seeded.onBarsRestoredExternally()

        assertTrue(seeded.isImmersive.value)
        assertTrue(seeded.systemBarsHiddenForTest)
        assertEquals(1, freshController.hideCount)
    }

    @Test
    fun sleepResumeFlowKeepsNavBarHiddenWhenImmersiveBeforeSleep() {
        state.hide()
        assertEquals(1, controller.hideCount)

        state.hide(force = true)

        assertEquals(2, controller.hideCount)
        assertTrue(state.isImmersive.value)
    }
}
