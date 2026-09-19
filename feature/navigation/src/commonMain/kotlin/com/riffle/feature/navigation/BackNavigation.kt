package com.riffle.feature.navigation

/**
 * Whether the top-level Back handler (and the library screen's backEnabled) should intercept
 * Back and close the drawer instead of letting the navigation host pop the current destination.
 *
 * Both [drawerCurrentOpen] and [drawerTargetOpen] must be checked:
 * - [drawerTargetOpen] flips true the instant the drawer is asked to open → covers Back
 *   pressed during the open animation.
 * - [drawerCurrentOpen] stays true until the close animation finishes → covers Back pressed
 *   during the close animation (the target is already Closed at that point).
 */
fun shouldInterceptBackForDrawer(
    usePermanentDrawer: Boolean,
    drawerCurrentOpen: Boolean,
    drawerTargetOpen: Boolean,
): Boolean = !usePermanentDrawer && (drawerCurrentOpen || drawerTargetOpen)

/**
 * Whether the library-items screen's Back handler should be enabled.
 *
 * Two conditions must both hold:
 * 1. [committedRoute] (top of the COMMITTED back stack) is the library-items destination. Using
 *    the committed route (not the preview the navigation host exposes mid-gesture) keeps the
 *    handler armed even while a predictive-back gesture FROM library_items is in progress — the
 *    committed top stays library_items until the gesture commits, so the handler intercepts and
 *    runs ClearSearch/ResetTab/Exit instead of letting the host pop library_items and flash the
 *    HOME spinner. When library_item_detail is the committed top (user navigated into a
 *    sub-screen), the handler is disabled and the host handles its own predictive-back pop
 *    animation for the sub-screen.
 * 2. The drawer is not open or animating — when it is, the top-level drawer Back handler must
 *    take Back to close the drawer (see [shouldInterceptBackForDrawer]).
 */
fun libraryItemsBackEnabled(
    committedRoute: String?,
    usePermanentDrawer: Boolean,
    drawerCurrentOpen: Boolean,
    drawerTargetOpen: Boolean,
): Boolean = committedRoute?.startsWith("library_items/") == true &&
    !shouldInterceptBackForDrawer(usePermanentDrawer, drawerCurrentOpen, drawerTargetOpen)

/**
 * Extracts the committed top route from a back stack, ignoring graph-root entries that have
 * no route string. Used by [libraryItemsBackEnabled] so it operates on the COMMITTED top, not
 * the predictive-back preview destination the navigation host temporarily reflects.
 */
fun committedTopRoute(backStackRoutes: List<String?>): String? =
    backStackRoutes.lastOrNull { it != null }

/**
 * Calls [action] only if [isStillTop] returns true.
 *
 * Guards back-navigation callbacks in sub-screens against double-pops during exit animations.
 * When a screen exits, its view stays alive for the animation duration (~300ms). A second tap
 * on the ← button during that window re-fires the back callback, but the committed back stack
 * has already advanced — [isStillTop] catches this and skips the pop to prevent removing the
 * wrong entry (e.g. library_items → HOME spinner).
 */
fun guardedNavigateBack(isStillTop: () -> Boolean, action: () -> Unit): Boolean {
    if (!isStillTop()) return false
    action()
    return true
}
