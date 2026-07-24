package com.riffle.app.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the fix for the burger-tap freeze (permanent white-screen-with-spinner):
 *
 * Before, the top-level drawer BackHandler was gated on `drawerState.isOpen`, which reads
 * `currentValue == Open` — only true after the open animation completes. A Back pressed during
 * the animation window fell through to the NavHost callback, popped the library entry, and
 * surfaced HOME; HOME's LaunchedEffect re-ran getStartDestination(), and any transient hang
 * inside wedged the app on a white spinner with no escape.
 *
 * The fix gates on `drawerState.targetValue == Open` — which flips true the instant
 * `drawerState.open()` is invoked from the burger tap. This test locks in the pure decision;
 * `MainScreen.kt`'s call site must pass `targetValue == DrawerValue.Open` into the second
 * argument. If someone flips it back to `isOpen`, the composable's expression stops mapping to
 * "target open," but the intent still holds only if the predicate contract stays as tested here.
 */
class ShouldInterceptBackForDrawerTest {

    @Test
    fun `intercepts when drawer is targeting open on a phone (modal drawer)`() {
        assertTrue(shouldInterceptBackForDrawer(usePermanentDrawer = false, drawerTargetOpen = true))
    }

    @Test
    fun `does not intercept when drawer is targeting closed`() {
        assertFalse(shouldInterceptBackForDrawer(usePermanentDrawer = false, drawerTargetOpen = false))
    }

    @Test
    fun `never intercepts on tablets (permanent drawer has no Back semantics)`() {
        assertFalse(shouldInterceptBackForDrawer(usePermanentDrawer = true, drawerTargetOpen = true))
        assertFalse(shouldInterceptBackForDrawer(usePermanentDrawer = true, drawerTargetOpen = false))
    }
}
