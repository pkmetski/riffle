package com.riffle.app.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

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
 * After the AppShell refactor, drawer section navigation moved into AppShellContent. The
 * deduplication guard is now inside AppShell.kt:
 *   onSwitchToSection = { section -> if (section != appSection) { ... } }
 * rather than a NavController route check in MainScreen.kt.
 *
 * The assertions below flip red if the guard is removed from AppShell.kt.
 */
class DrawerNavigationDeduplicationTest {

    @Test
    fun `Downloads drawer navigation is guarded by route check`() {
        assertSectionSwitchHasGuard("AppSection.Downloads")
    }

    @Test
    fun `Settings drawer navigation is guarded by route check`() {
        assertSectionSwitchHasGuard("AppSection.Settings")
    }

    private fun assertSectionSwitchHasGuard(sectionRef: String) {
        val source = locateAppShellSource()
        val lines = source.readLines()
        val sectionSwitchIdx = lines.indexOfFirst { "onSwitchToSection" in it && "section ->" in it }
        assertTrue("onSwitchToSection lambda not found in AppShell.kt", sectionSwitchIdx >= 0)
        val window = lines.subList(sectionSwitchIdx, minOf(sectionSwitchIdx + 5, lines.size)).joinToString("\n")
        assertTrue(
            "onSwitchToSection must guard against re-selecting the current section " +
                "(if (section != appSection)) to prevent spurious re-entry. Found:\n$window",
            "section != appSection" in window,
        )
    }

    private fun locateAppShellSource(): File =
        listOf(
            File("feature/library-ui/src/commonMain/kotlin/com/riffle/feature/library/ui/AppShell.kt"),
            File("../feature/library-ui/src/commonMain/kotlin/com/riffle/feature/library/ui/AppShell.kt"),
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
