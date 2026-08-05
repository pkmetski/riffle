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

/**
 * Pins the fix for the sub-screen Back intercept: Compose Navigation 2.8+ keeps the previous
 * back-stack entry in composition for predictive-back animations, so LibraryItemsScreen's
 * BackHandler remains registered (and enabled) while library_item_detail is foreground. Without
 * the currentRoute gate, pressing Back from a sub-screen fires the library BackHandler instead
 * of the NavHost pop — causing unexpected app-exit or blank-screen navigation.
 *
 * Assertion that flips red if the currentRoute guard is removed from libraryItemsBackEnabled:
 * the assertFalse cases below would return true, re-enabling the handler on sub-screens.
 */
class LibraryItemsBackEnabledTest {

    @Test
    fun `enabled when library_items is foreground and drawer is closed`() {
        assertTrue(libraryItemsBackEnabled(
            currentRoute = "library_items/lib-1/Books",
            usePermanentDrawer = false,
            drawerCurrentOpen = false,
            drawerTargetOpen = false,
        ))
    }

    @Test
    fun `disabled when library_item_detail is foreground (predictive-back preview)`() {
        assertFalse(libraryItemsBackEnabled(
            currentRoute = "library_item_detail/item-1",
            usePermanentDrawer = false,
            drawerCurrentOpen = false,
            drawerTargetOpen = false,
        ))
    }

    @Test
    fun `disabled when series_detail is foreground`() {
        assertFalse(libraryItemsBackEnabled(
            currentRoute = "series_detail/lib-1/series-1/MySeries",
            usePermanentDrawer = false,
            drawerCurrentOpen = false,
            drawerTargetOpen = false,
        ))
    }

    @Test
    fun `disabled when reader is foreground`() {
        assertFalse(libraryItemsBackEnabled(
            currentRoute = "epub_reader/item-1",
            usePermanentDrawer = false,
            drawerCurrentOpen = false,
            drawerTargetOpen = false,
        ))
    }

    @Test
    fun `disabled when library_items is foreground but drawer is open`() {
        assertFalse(libraryItemsBackEnabled(
            currentRoute = "library_items/lib-1/Books",
            usePermanentDrawer = false,
            drawerCurrentOpen = true,
            drawerTargetOpen = true,
        ))
    }

    @Test
    fun `disabled when route is null (initial navigation not yet resolved)`() {
        assertFalse(libraryItemsBackEnabled(
            currentRoute = null,
            usePermanentDrawer = false,
            drawerCurrentOpen = false,
            drawerTargetOpen = false,
        ))
    }
}
