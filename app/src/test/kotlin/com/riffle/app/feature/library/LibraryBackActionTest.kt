package com.riffle.app.feature.library

import android.app.Activity
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryBackActionTest {

    @Test
    fun `non-empty search query yields ClearSearch regardless of tab`() {
        assertEquals(LibraryBackAction.ClearSearch, libraryBackAction(searchQuery = "dune", selectedTab = 0))
        assertEquals(LibraryBackAction.ClearSearch, libraryBackAction(searchQuery = "dune", selectedTab = 3))
    }

    @Test
    fun `empty query with non-Home tab yields ResetToHomeTab`() {
        assertEquals(LibraryBackAction.ResetToHomeTab, libraryBackAction(searchQuery = "", selectedTab = 1))
        assertEquals(LibraryBackAction.ResetToHomeTab, libraryBackAction(searchQuery = "", selectedTab = 4))
    }

    @Test
    fun `empty query with Home tab yields Exit`() {
        assertEquals(LibraryBackAction.Exit, libraryBackAction(searchQuery = "", selectedTab = 0))
    }

    // Pins the fix for the burger-menu infinite-loop (Android 12+ singleTop): activity.finish()
    // caused the activity to be re-created in the same process and the Back event to be
    // re-delivered, creating a finish→recreate→Back→finish loop. moveTaskToBack(true) is the
    // correct exit: it backgrounds the task without destroying the activity.
    @Test
    fun `handleLibraryExit calls moveTaskToBack(true) not finish`() {
        val activity = mockk<Activity>(relaxed = true)
        handleLibraryExit(activity)
        verify { activity.moveTaskToBack(true) }
        verify(exactly = 0) { activity.finish() }
    }

    @Test
    fun `handleLibraryExit is safe when activity is null`() {
        handleLibraryExit(null)
    }
}
