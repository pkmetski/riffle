package com.riffle.app.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fix for the burger-tap freeze (permanent white-screen-with-spinner):
 *
 * The top-level drawer BackHandler was originally gated on `drawerState.isOpen`
 * (== currentValue == Open), which only becomes true after the open animation completes.
 * A Back pressed during the open-animation window fell through to the NavHost callback,
 * popped the library entry, surfaced HOME, and any transient hang in getStartDestination()
 * wedged the app on a white spinner with no escape.
 *
 * The fix gates on BOTH currentValue and targetValue:
 * - targetValue flips to Open the instant drawerState.open() is called → catches Back
 *   during the open animation.
 * - currentValue stays Open until the close animation completes → catches Back during
 *   the close animation (targetValue is already Closed at that point, so isOpen alone
 *   would miss it in the opposite direction).
 *
 * The same predicate also gates LibraryItemsScreen's backEnabled, because that child
 * BackHandler has LIFO priority over the top-level handler and would otherwise intercept
 * Back first during the animation windows.
 */
class ShouldInterceptBackForDrawerTest {

    @Test
    fun `intercepts when drawer is fully open on a phone`() {
        assertTrue(shouldInterceptBackForDrawer(usePermanentDrawer = false, drawerCurrentOpen = true, drawerTargetOpen = true))
    }

    @Test
    fun `intercepts during open animation — targetValue flipped but currentValue not yet`() {
        assertTrue(shouldInterceptBackForDrawer(usePermanentDrawer = false, drawerCurrentOpen = false, drawerTargetOpen = true))
    }

    @Test
    fun `intercepts during close animation — currentValue still Open but targetValue already Closed`() {
        assertTrue(shouldInterceptBackForDrawer(usePermanentDrawer = false, drawerCurrentOpen = true, drawerTargetOpen = false))
    }

    @Test
    fun `does not intercept when drawer is fully closed`() {
        assertFalse(shouldInterceptBackForDrawer(usePermanentDrawer = false, drawerCurrentOpen = false, drawerTargetOpen = false))
    }

    @Test
    fun `never intercepts on tablets (permanent drawer has no Back semantics)`() {
        assertFalse(shouldInterceptBackForDrawer(usePermanentDrawer = true, drawerCurrentOpen = true, drawerTargetOpen = true))
        assertFalse(shouldInterceptBackForDrawer(usePermanentDrawer = true, drawerCurrentOpen = false, drawerTargetOpen = true))
        assertFalse(shouldInterceptBackForDrawer(usePermanentDrawer = true, drawerCurrentOpen = true, drawerTargetOpen = false))
        assertFalse(shouldInterceptBackForDrawer(usePermanentDrawer = true, drawerCurrentOpen = false, drawerTargetOpen = false))
    }
}
