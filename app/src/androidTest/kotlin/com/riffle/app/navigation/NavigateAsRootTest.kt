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
 * The graph's start destination (HOME) is popped inclusively as soon as a library resolves at
 * launch, so the old `popUpTo(HOME) { inclusive = true }` used by every root-switch navigation
 * matched nothing afterwards. Each server/library switch then pushed a *duplicate* library_items
 * root instead of replacing it, and backing out of the accumulated roots could empty the back
 * stack entirely — leaving the NavHost with no destination (a blank screen).
 *
 * [navigateAsRoot] anchors the pop on the root graph id instead, so a single destination is always
 * the sole root.
 */
@RunWith(AndroidJUnit4::class)
class NavigateAsRootTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun libraryRoots(nav: NavHostController) =
        nav.currentBackStack.value.count { it.destination.route?.startsWith("lib/") == true }

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

        // Launch router: resolve a library and pop HOME inclusively (mirrors HomeScreen).
        composeTestRule.runOnUiThread { nav.navigateAsRoot("lib/a") }
        composeTestRule.waitForIdle()
        assertEquals("HOME should be gone after launch", null, currentRoute(nav, "home"))

        // Switch libraries several times — each must REPLACE the root, never accumulate.
        composeTestRule.runOnUiThread { nav.navigateAsRoot("lib/b") }
        composeTestRule.runOnUiThread { nav.navigateAsRoot("lib/c") }
        composeTestRule.waitForIdle()
        assertEquals("library root must not accumulate on switch", 1, libraryRoots(nav))

        // Drill into a detail, then back out: the stack must return to the single root, never empty.
        composeTestRule.runOnUiThread { nav.navigate("detail/x") }
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { nav.popBackStack() }
        composeTestRule.waitForIdle()

        assertEquals("back from detail returns to the single library root", 1, libraryRoots(nav))
        assertTrue(
            "back stack must still have a current destination (not blank)",
            nav.currentBackStackEntry?.destination?.route != null,
        )
    }

    private fun currentRoute(nav: NavHostController, route: String): String? =
        nav.currentBackStack.value.firstOrNull { it.destination.route == route }?.destination?.route
}
