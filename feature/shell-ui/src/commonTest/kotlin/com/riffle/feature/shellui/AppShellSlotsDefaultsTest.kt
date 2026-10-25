package com.riffle.feature.shellui

import com.riffle.feature.navigation.LibraryNav
import kotlin.test.Test
import kotlin.test.assertFalse

class AppShellSlotsDefaultsTest {

    @Test
    fun annotationSearchIsNotAReaderDestination() {
        val stack = listOf(LibraryNav.Items, LibraryNav.AnnotationSearch("lib-1", ""))
        assertFalse(isReaderActiveForStack(stack))
    }
}
