package com.riffle.app.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * The same predicate also gates LibraryItemsScreen's backEnabled as belt-and-suspenders:
 * the drawer BackHandler is now registered AFTER NavHost (higher LIFO priority in
 * OnBackPressedDispatcher) so it fires before the library handler. Disabling the library
 * handler when the drawer is open avoids any residual edge-case race.
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
            committedRoute = "library_items/lib-1/Books",
            usePermanentDrawer = false,
            drawerCurrentOpen = false,
            drawerTargetOpen = false,
        ))
    }

    // Pins the fix for the burger-menu freeze when swiping back FROM library_items:
    // the committed route stays library_items while the predictive-back gesture is in progress
    // (the gesture hasn't committed yet), so the handler stays armed and intercepts the back
    // to run ClearSearch/ResetTab/Exit instead of letting NavHost pop library_items.
    @Test
    fun `enabled during predictive-back FROM library_items (committed top is still library_items)`() {
        assertTrue(libraryItemsBackEnabled(
            committedRoute = "library_items/lib-1/Books",
            usePermanentDrawer = false,
            drawerCurrentOpen = false,
            drawerTargetOpen = false,
        ))
    }

    @Test
    fun `disabled when library_item_detail is foreground (predictive-back preview)`() {
        assertFalse(libraryItemsBackEnabled(
            committedRoute = "library_item_detail/item-1",
            usePermanentDrawer = false,
            drawerCurrentOpen = false,
            drawerTargetOpen = false,
        ))
    }

    @Test
    fun `disabled when series_detail is foreground`() {
        assertFalse(libraryItemsBackEnabled(
            committedRoute = "series_detail/lib-1/series-1/MySeries",
            usePermanentDrawer = false,
            drawerCurrentOpen = false,
            drawerTargetOpen = false,
        ))
    }

    @Test
    fun `disabled when reader is foreground`() {
        assertFalse(libraryItemsBackEnabled(
            committedRoute = "epub_reader/item-1",
            usePermanentDrawer = false,
            drawerCurrentOpen = false,
            drawerTargetOpen = false,
        ))
    }

    @Test
    fun `disabled when library_items is foreground but drawer is open`() {
        assertFalse(libraryItemsBackEnabled(
            committedRoute = "library_items/lib-1/Books",
            usePermanentDrawer = false,
            drawerCurrentOpen = true,
            drawerTargetOpen = true,
        ))
    }

    @Test
    fun `disabled when route is null (initial navigation not yet resolved)`() {
        assertFalse(libraryItemsBackEnabled(
            committedRoute = null,
            usePermanentDrawer = false,
            drawerCurrentOpen = false,
            drawerTargetOpen = false,
        ))
    }
}

/**
 * Pins the role of committedTopRoute() in the isCommittedOnLibraryItems runtime check:
 *
 * LibraryItemsScreen's BackHandler uses currentRoute (the preview destination) for its enabled
 * gate — no 16ms recomposition lag. Inside the handler, isCommittedOnLibraryItems() reads
 * navController.currentBackStack.value synchronously via committedTopRoute() to decide:
 *   - library_items is the committed top → run library back actions (clear search, reset tab, exit)
 *   - sub-screen is the committed top  → call onNavigateBack() to pop the sub-screen
 *
 * The two cases arise during a predictive-back gesture from library_item_detail (library_items
 * shows as the preview background, library_item_detail is still the committed top) and during the
 * brief recomposition window right after a commit (both Compose state sources are momentarily stale,
 * but navController.currentBackStack.value is always synchronously up-to-date).
 */
class CommittedTopRouteTest {

    @Test
    fun `returns last non-null route from back stack`() {
        // During predictive back from library_item_detail → library_items, the committed back stack
        // is [home, library_items/..., library_item_detail/...]. committedTopRoute must return
        // library_item_detail — isCommittedOnLibraryItems() returns false → onNavigateBack() fires.
        val routes = listOf("home", "library_items/lib-1/Books", "library_item_detail/item-1")
        assertEquals("library_item_detail/item-1", committedTopRoute(routes))
    }

    @Test
    fun `during predictive-back from sub-screen, isCommittedOnLibraryItems returns false`() {
        // During the predictive-back gesture, navController.currentBackStack.value still has
        // library_item_detail on top (it hasn't committed yet). committedTopRoute returns
        // "library_item_detail/..." → startsWith("library_items/") == false → onNavigateBack().
        val routes = listOf(null, "home", "library_items/lib-1/Books", "library_item_detail/item-1")
        val committedTop = committedTopRoute(routes)
        assertFalse(
            "isCommittedOnLibraryItems must be false while sub-screen is committed top",
            committedTop?.startsWith("library_items/") == true,
        )
    }

    @Test
    fun `when library_items is committed top, isCommittedOnLibraryItems returns true`() {
        // After the sub-screen back commits (and navController.currentBackStack.value is updated),
        // committedTopRoute returns "library_items/..." → isCommittedOnLibraryItems() = true.
        val routes = listOf(null, "home", "library_items/lib-1/Books")
        val committedTop = committedTopRoute(routes)
        assertTrue(
            "isCommittedOnLibraryItems must be true once library_items is the committed top",
            committedTop?.startsWith("library_items/") == true,
        )
    }

    @Test
    fun `returns null for empty back stack`() {
        assertNull(committedTopRoute(emptyList()))
    }

    @Test
    fun `skips null entries (graph root has no route string)`() {
        assertEquals("home", committedTopRoute(listOf(null, "home")))
    }
}

/**
 * Pins the double-tap guard fix for the burger-menu freeze.
 *
 * Root cause: the ← back button on library_item_detail sits at the same screen coordinates as
 * ☰ on library_items. Compose's exit animation keeps library_item_detail alive for ~300ms after
 * the first tap commits the pop. A second tap during that window re-fires onNavigateBack → a
 * second navController.popBackStack() removes library_items and surfaces HOME → white spinner.
 *
 * The fix wraps the pop in guardedNavigateBack(), which checks isStillTop() before popping.
 * At double-tap time the entry is already gone from the committed back stack, so isStillTop()
 * returns false and the second pop is suppressed.
 *
 * Assertion that flips red if the guard is removed: the `does not call popBack` test would fail
 * because popBack would be called even when isStillTop() returns false.
 */
class GuardedNavigateBackTest {

    @Test
    fun `calls popBack when entry is still the committed top`() {
        var called = false
        val acted = guardedNavigateBack(isStillTop = { true }, action = { called = true })
        assertTrue(acted)
        assertTrue(called)
    }

    @Test
    fun `does not call popBack when entry is no longer the committed top (double-tap during exit animation)`() {
        var called = false
        val acted = guardedNavigateBack(isStillTop = { false }, action = { called = true })
        assertFalse(acted)
        assertFalse(called)
    }
}

/**
 * Keeps the fix at the route-boundary instead of per-screen patches.
 *
 * Any route-level UI back callback that calls navController.popBackStack() directly can fire
 * again while its composable is retained for an exit/predictive-back animation. That second
 * fire pops the entry underneath the exiting screen, which is the bug this branch is fixing.
 *
 * Assertion that flips red if a future route reintroduces the raw pattern: this test will report
 * the offending MainScreen.kt line and force the callback through popBackStackIfTop(backStackEntry)
 * or an explicitly named exception helper.
 */
class RouteBackCallbackGuardrailTest {

    @Test
    fun `MainScreen does not call navController popBackStack directly`() {
        val source = locateMainScreenSource()
        val offenders = source.readLines()
            .mapIndexedNotNull { index, line ->
                if ("navController.popBackStack()" in line) "${index + 1}: ${line.trim()}" else null
            }

        assertTrue(
            "Route callbacks must use a guarded helper instead of raw navController.popBackStack():\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun locateMainScreenSource(): File =
        listOf(
            File("src/main/kotlin/com/riffle/app/navigation/MainScreen.kt"),
            File("app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt"),
        ).first { it.exists() }
}

/**
 * Pins the fix for Downloads and Settings being re-added to the back stack when re-selected from
 * the drawer while already on that screen.
 *
 * Previously both used a plain navController.navigate(DOWNLOADS/SETTINGS) with no guard, so
 * tapping the drawer item while already on the screen pushed a duplicate entry. Back then had to
 * be pressed once per duplicate before returning to the previous destination.
 *
 * The fix guards each navigate call with a currentDestination?.route check so re-selecting an
 * already-top destination is a no-op. The assertions below flip red if either guard is removed.
 *
 * Note: `launchSingleTop = true` was intentionally NOT used — it still fires the enter animation
 * on the existing composable in Navigation 2.9+, producing a bizarre re-enter animation when the
 * destination is already the top of the stack.
 */
class DrawerNavigationDeduplicationTest {

    @Test
    fun `Downloads drawer navigation is guarded by route check`() {
        assertNavCallHasRouteGuard("DOWNLOADS", "downloads")
    }

    @Test
    fun `Settings drawer navigation is guarded by route check`() {
        assertNavCallHasRouteGuard("SETTINGS", "settings")
    }

    private fun assertNavCallHasRouteGuard(constName: String, literal: String) {
        val lines = locateMainScreenSource().readLines()
        val idx = lines.indexOfFirst { line ->
            "navigate($constName)" in line || "navigate(\"$literal\")" in line
        }
        assertTrue("$constName navigate call not found in MainScreen.kt", idx >= 0)
        // The guard must appear on the same line or the immediately preceding line.
        val window = lines.subList(maxOf(0, idx - 1), minOf(idx + 2, lines.size)).joinToString("\n")
        assertTrue(
            "navigate($constName) must be guarded by a currentDestination route check to prevent " +
                "back-stack duplicates and spurious re-enter animations.\nFound:\n$window",
            "currentDestination" in window,
        )
    }

    private fun locateMainScreenSource(): File =
        listOf(
            File("src/main/kotlin/com/riffle/app/navigation/MainScreen.kt"),
            File("app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt"),
        ).first { it.exists() }
}

/**
 * Pins the migration from `androidx.navigation` to `org.jetbrains.androidx.navigation`.
 *
 * Both `androidx.navigation` and `org.jetbrains.androidx.navigation` (the JetBrains Compose
 * Multiplatform fork) annotate `NavController.currentBackStack` as `@RestrictedApi`. The
 * suppression is intentional and must remain at each use site in MainScreen.kt.
 *
 * This test pins the dependency swap itself: it would flip red if the gradle.kts were reverted
 * to use `androidx.navigation` instead of `org.jetbrains.androidx.navigation`.
 */
class NavJetBrainsApiGuardrailTest {

    @Test
    fun `app module uses JetBrains multiplatform navigation, not androidx navigation`() {
        val versionsToml = locateVersionsCatalog()
        val lines = versionsToml.readLines()

        val hasJetbrainsNav = lines.any { line ->
            line.contains("jetbrains-navigation") && line.contains("org.jetbrains.androidx.navigation")
        }
        assertTrue(
            "libs.versions.toml must declare org.jetbrains.androidx.navigation under " +
                "a 'jetbrains-navigation' key. If this fails, the dep was reverted to " +
                "androidx.navigation — restore the JetBrains library entry.",
            hasJetbrainsNav,
        )

        val buildGradle = locateNavSource("build.gradle.kts")
        val usesJetbrainsNav = buildGradle.readLines().any { line ->
            "jetbrains.navigation.compose" in line && !line.trimStart().startsWith("//")
        }
        assertTrue(
            "app/build.gradle.kts must use libs.jetbrains.navigation.compose (not " +
                "libs.androidx.navigation.compose). If this fails, the dep swap was reverted.",
            usesJetbrainsNav,
        )
    }

    private fun locateVersionsCatalog(): File =
        listOf(
            File("gradle/libs.versions.toml"),
            File("../gradle/libs.versions.toml"),
        ).first { it.exists() }

    private fun locateNavSource(fileName: String): File =
        listOf(
            File(fileName),
            File("app/$fileName"),
        ).first { it.exists() }
}
