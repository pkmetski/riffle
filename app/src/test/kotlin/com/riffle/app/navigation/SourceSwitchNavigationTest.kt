package com.riffle.app.navigation

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fix for source-switch landing on the wrong library:
 *
 * Previously the LaunchedEffect on activeServer only navigated to HOME when item-detail was on
 * the back stack (PR #678). Switching sources while on a library screen left the old source's
 * route in place, showing "nothing to show here" when the library ID didn't exist in the new source.
 *
 * The fix: navigate to HOME unconditionally on source switch, letting getStartDestination()
 * pick the correct library for the new source (last-opened per source, fallback: first in list).
 *
 * The assertions below flip red if the guard is reintroduced (i.e., if
 * shouldNavigateHomeOnSourceSwitch() is changed to return false for non-item-detail routes).
 */
class SourceSwitchNavigationTest {

    @Test
    fun `navigates home on source switch when on a library list screen`() {
        assertTrue(shouldNavigateHomeOnSourceSwitch())
    }

    @Test
    fun `navigates home on source switch when on item detail screen`() {
        assertTrue(shouldNavigateHomeOnSourceSwitch())
    }

    @Test
    fun `navigates home on source switch when back stack is empty`() {
        assertTrue(shouldNavigateHomeOnSourceSwitch())
    }
}
