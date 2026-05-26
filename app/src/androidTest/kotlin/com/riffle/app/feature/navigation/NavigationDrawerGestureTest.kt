package com.riffle.app.feature.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that RiffleNavigationDrawer respects the gesturesEnabled flag.
 *
 * Regression: when gesturesEnabled was always true, ModalNavigationDrawer's horizontal-drag
 * detector intercepted the left/right edge taps Readium uses for EPUB page navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class NavigationDrawerGestureTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun drawerDoesNotOpenViaGestureWhenGesturesDisabled() {
        val drawerState = DrawerState(DrawerValue.Closed)
        composeTestRule.setContent {
            RiffleNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = false,
                activeServer = null,
                allServers = emptyList(),
                visibleLibraries = emptyList(),
                activeLibraryId = null,
                serverVersion = null,
                onDrawerOpened = {},
                onServerSelected = {},
                onLibrarySelected = {},
                onDownloadsSelected = {},
                onSettingsSelected = {},
            ) {
                Box(Modifier.fillMaxSize().testTag("content"))
            }
        }

        composeTestRule.onNodeWithTag("content").performTouchInput {
            down(Offset(0f, centerY))
            moveBy(Offset(width * 0.7f, 0f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(DrawerValue.Closed, drawerState.currentValue)
    }

}
