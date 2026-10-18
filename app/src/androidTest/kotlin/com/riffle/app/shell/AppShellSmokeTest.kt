package com.riffle.app.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.MainActivity
import com.riffle.core.database.RiffleDatabaseAccess
import com.riffle.core.database.SourceEntity
import com.riffle.core.database.clearAllTables
import com.riffle.feature.designsystem.TestTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.test.KoinTest
import org.koin.test.inject

/**
 * Smoke test verifying that the AppShell renders correctly inside the Android host.
 *
 * Confirms that [com.riffle.feature.shellui.AppShell] is wired to [MainActivity] via
 * [com.riffle.app.navigation.MainScreen] and that the navigation drawer toggle and drawer items
 * (Settings, Downloads) that the shared shell renders are present in the UI.
 *
 * A fake ABS source is inserted in [setUp] so the shell proceeds past onboarding to the
 * library section where the drawer is visible.
 */
@RunWith(AndroidJUnit4::class)
class AppShellSmokeTest : KoinTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val database: RiffleDatabaseAccess by inject()

    @Before
    fun setUp() {
        runBlocking {
            database.clearAllTables()
            database.sourceDao().upsert(
                SourceEntity(
                    id = "smoke-test-source",
                    url = "http://localhost:13378",
                    isActive = true,
                    insecureConnectionAllowed = true,
                    username = "test",
                ),
            )
        }
    }

    @After
    fun tearDown() {
        database.clearAllTables()
    }

    @Test
    fun drawerToggleIsPresent() {
        composeTestRule.onNodeWithTag(TestTags.NAV_DRAWER_TOGGLE).assertIsDisplayed()
    }

    @Test
    fun settingsDrawerEntryIsReachable() {
        composeTestRule.onNodeWithTag(TestTags.NAV_DRAWER_TOGGLE).performClick()
        composeTestRule.onNodeWithTag(TestTags.NAV_DRAWER_SETTINGS).assertIsDisplayed()
    }

    @Test
    fun downloadsDrawerEntryIsReachable() {
        composeTestRule.onNodeWithTag(TestTags.NAV_DRAWER_TOGGLE).performClick()
        composeTestRule.onNodeWithTag(TestTags.NAV_DRAWER_DOWNLOADS).assertIsDisplayed()
    }
}
