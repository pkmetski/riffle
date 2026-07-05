package com.riffle.app.feature.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.core.domain.Library
import com.riffle.core.domain.Server
import com.riffle.core.domain.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the "Annotations" drawer entry (Task 4 of the Annotations View feature):
 * it must appear inside the scrollable visible-libraries cluster, above the
 * Downloads/Settings divider, and invoke the supplied callback on tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class NavigationDrawerAnnotationsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun annotationsEntry_displayedAndInvokesCallbackOnce() {
        val server = Server(
            id = "s1",
            url = ServerUrl.parse("http://example.com")!!,
            isActive = true,
            insecureConnectionAllowed = false,
            username = "alice",
        )
        val libraries = listOf(
            Library(id = "lib1", name = "Library One", mediaType = "book", isUnsupported = false),
        )
        var annotationsSelectedCount = 0

        composeTestRule.setContent {
            RiffleNavigationDrawer(
                drawerState = DrawerState(DrawerValue.Open) { true },
                activeServer = server,
                allServers = listOf(server),
                visibleLibraries = libraries,
                activeLibraryId = "lib1",
                serverVersions = emptyMap(),
                onServerSelected = {},
                onLibrarySelected = {},
                onAnnotationsSelected = { annotationsSelectedCount++ },
                onDownloadsSelected = {},
                onSettingsSelected = {},
            ) {
                Box(Modifier.fillMaxSize())
            }
        }

        composeTestRule.onNodeWithText("Annotations").assertIsDisplayed()

        val libraryY = composeTestRule.onNodeWithText("Library One").getBoundsInRoot().top
        val annotationsY = composeTestRule.onNodeWithText("Annotations").getBoundsInRoot().top
        val downloadsY = composeTestRule.onNodeWithText("Downloads").getBoundsInRoot().top
        assertTrue(
            "Annotations must render below Library One and above Downloads",
            libraryY < annotationsY && annotationsY < downloadsY,
        )

        composeTestRule.onNodeWithText("Annotations").performClick()

        assertEquals(1, annotationsSelectedCount)
    }
}
