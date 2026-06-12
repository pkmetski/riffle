package com.riffle.app.navigation

import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression: an intermittent blank white screen ("burger menu blank").
 *
 * Every root-switch navigation goes through [navigateAsRoot]. It keeps HOME as the permanent base
 * of the back stack (popUpTo(HOME) { inclusive = false }) so the active surface always sits ON TOP
 * of home — the stack is [home, route], never a single sole entry.
 *
 * Two failure modes this guards against:
 *  - **Blank screen.** If the active surface were the *only* entry, a Back that reaches the
 *    NavHost's own callback (e.g. while the drawer is animating open, when no screen-level handler
 *    owns it) pops that lone entry to an EMPTY back stack — a blank NavHost. Keeping home beneath
 *    it means such a Back lands on home instead.
 *  - **Duplicate roots.** The original popUpTo(HOME) { inclusive = true } removed home, so later
 *    popUpTo(HOME) calls matched nothing and stacked duplicate roots. Never removing home keeps
 *    popUpTo(HOME) matching, so each switch replaces the surface.
 */
@RunWith(AndroidJUnit4::class)
class NavigateAsRootTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun libraryRoots(nav: NavHostController) =
        nav.currentBackStack.value.count { it.destination.route?.startsWith("lib/") == true }

    private fun hasRoute(nav: NavHostController, route: String): Boolean =
        nav.currentBackStack.value.any { it.destination.route == route }

    @Test
    fun switchingRootsNeverAccumulatesOrEmptiesBackStack() {
        lateinit var nav: NavHostController
        composeTestRule.setContent {
            nav = rememberNavController()
            NavHost(navController = nav, startDestination = "home") {
                composable("home") {}
                composable("lib/{id}") {}
                composable("detail/{id}") {}
            }
        }

        // Launch router: resolve a library (mirrors HomeScreen). HOME must REMAIN as the base.
        composeTestRule.runOnUiThread { nav.navigateAsRoot("lib/a") }
        composeTestRule.waitForIdle()
        assertTrue("HOME must stay at the base of the stack", hasRoute(nav, "home"))
        // destination.route is the registered template; the resolved arg confirms WHICH library.
        assertEquals("a library surface is on top", "lib/{id}", nav.currentBackStackEntry?.destination?.route)
        assertEquals("specifically lib/a", "a", nav.currentBackStackEntry?.arguments?.getString("id"))

        // Switch libraries several times — each must REPLACE the surface, never accumulate.
        composeTestRule.runOnUiThread { nav.navigateAsRoot("lib/b") }
        composeTestRule.runOnUiThread { nav.navigateAsRoot("lib/c") }
        composeTestRule.waitForIdle()
        assertEquals("library root must not accumulate on switch", 1, libraryRoots(nav))
        assertTrue("HOME still the base after switches", hasRoute(nav, "home"))

        // Drill into a detail, then back out: returns to the single library surface.
        composeTestRule.runOnUiThread { nav.navigate("detail/x") }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { nav.popBackStack() }
        composeTestRule.waitForIdle()
        assertEquals("back from detail returns to the single library surface", 1, libraryRoots(nav))

        // THE blank-screen case: a Back that reaches the NavHost pops the library *root* itself.
        // It must land on HOME — never an empty stack with no current destination.
        composeTestRule.runOnUiThread { nav.popBackStack() }
        composeTestRule.waitForIdle()
        assertEquals(
            "popping the root surface lands on HOME, not a blank NavHost",
            "home",
            nav.currentBackStackEntry?.destination?.route,
        )
    }
}
