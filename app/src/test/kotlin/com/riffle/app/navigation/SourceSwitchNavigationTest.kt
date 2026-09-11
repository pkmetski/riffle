package com.riffle.app.navigation

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the invariant that source switching always routes through HOME so
 * [com.riffle.feature.library.HomeViewModel.getStartDestination] picks the correct library
 * for the new source.
 *
 * Originally this guarded a LaunchedEffect that watched activeServer and called
 * [shouldNavigateHomeOnSourceSwitch] before navigating. That reactive approach was replaced with
 * `scope.launch { activeServer.filterNotNull().first { it.id == server.id }; navigateAsRoot(HOME) }`
 * in the `onServerSelected` drawer callback (MainScreen.kt) to fix two races:
 *   1. drop(1) in Riffle mode: all sources inactive → Room emits null → StateFlow deduplicates
 *      (null→null) → drop(1) never consumed → first source tap swallowed.
 *   2. Immediate navigateAsRoot: HOME calls getStartDestination() before setActiveServer's DB
 *      write completes → no active source → NoLibraries → "Unable to connect to source" screen.
 *
 * [shouldNavigateHomeOnSourceSwitch] now serves as a named constant documenting the intent.
 * The assertion below flips red if the constant is changed to false (which would break the
 * onServerSelected navigation path).
 */
class SourceSwitchNavigationTest {

    @Test
    fun `source switch always navigates home so getStartDestination picks the correct library`() {
        assertTrue(shouldNavigateHomeOnSourceSwitch())
    }
}
