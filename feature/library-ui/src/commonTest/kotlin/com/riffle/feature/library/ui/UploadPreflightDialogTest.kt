package com.riffle.feature.library.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.catalog.CatalogRoot
import com.riffle.feature.library.UploadDestination
import com.riffle.feature.library.UploadPreflight
import kotlin.test.Test

/**
 * Regression tests for UploadPreflightDialog.
 *
 * The shared screen (#1197) always showed the Overwrite button regardless of
 * UploadPreflight.ExistingItem.canOverwrite. These tests pin the correct behaviour:
 * - canOverwrite = false → Overwrite button absent, informational message shown
 * - canOverwrite = true  → Overwrite button present
 */
@OptIn(ExperimentalTestApi::class)
class UploadPreflightDialogTest {

    private val destination = UploadDestination(
        sourceId = "src-1",
        label = "My Library",
        username = "test",
        libraries = emptyList(),
    )

    private val library = CatalogRoot(
        id = "lib-1",
        name = "My Library",
        mediaType = "book",
    )

    @Test
    fun overwriteButtonAbsentWhenCanOverwriteIsFalse() = runComposeUiTest {
        setContent {
            UploadPreflightDialog(
                preflight = UploadPreflight.ExistingItem(
                    destination = destination,
                    library = library,
                    itemId = "item-1",
                    canOverwrite = false,
                ),
                onDismiss = {},
                onConfirmOverwrite = { _, _ -> },
            )
        }
        onNodeWithText("Overwrite").assertIsNotDisplayed()
    }

    @Test
    fun cannotOverwriteMessageShownWhenCanOverwriteIsFalse() = runComposeUiTest {
        setContent {
            UploadPreflightDialog(
                preflight = UploadPreflight.ExistingItem(
                    destination = destination,
                    library = library,
                    itemId = "item-1",
                    canOverwrite = false,
                ),
                onDismiss = {},
                onConfirmOverwrite = { _, _ -> },
            )
        }
        onNodeWithText(
            "Overwrite is blocked until the destination can safely replace files without invalidating annotations.",
        ).assertIsDisplayed()
    }

    @Test
    fun overwriteButtonPresentWhenCanOverwriteIsTrue() = runComposeUiTest {
        setContent {
            UploadPreflightDialog(
                preflight = UploadPreflight.ExistingItem(
                    destination = destination,
                    library = library,
                    itemId = "item-1",
                    canOverwrite = true,
                ),
                onDismiss = {},
                onConfirmOverwrite = { _, _ -> },
            )
        }
        onNodeWithText("Overwrite").assertIsDisplayed()
    }

    @Test
    fun idlePreflightRendersNothingVisible() = runComposeUiTest {
        setContent {
            UploadPreflightDialog(
                preflight = UploadPreflight.Idle,
                onDismiss = {},
                onConfirmOverwrite = { _, _ -> },
            )
        }
        onNodeWithText("Upload conflict").assertIsNotDisplayed()
        onNodeWithText("Cannot upload").assertIsNotDisplayed()
    }
}
