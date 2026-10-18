package com.riffle.feature.shellui

import com.riffle.feature.navigation.LibraryNav
import com.riffle.feature.navigation.isAlreadyAtPlayerDestination

/**
 * Returns true when the top of [navStack] is a reader destination whose content fills the
 * screen, hiding the navigation drawer. Mirrors the condition evaluated in
 * [LibraryHost]'s SideEffect to drive `onReaderActiveChanged`.
 *
 * Pure so it can be tested without a composable host.
 */
fun isReaderActiveForStack(navStack: List<LibraryNav>): Boolean {
    val last = navStack.lastOrNull() ?: return false
    return last is LibraryNav.Reader ||
        last is LibraryNav.PdfReader ||
        last is LibraryNav.CbzReader ||
        last is LibraryNav.ElidedReader
}

/**
 * Returns true when [incoming] is already covered by the top of [navStack] so that pushing
 * a duplicate now-playing destination is suppressed.
 *
 * Thin wrapper over [isAlreadyAtPlayerDestination] exposed so callers in shell-ui tests do
 * not need to depend on `:feature:navigation` directly.
 */
fun isDuplicateNowPlayingNav(navStack: List<LibraryNav>, incoming: LibraryNav): Boolean =
    isAlreadyAtPlayerDestination(navStack, incoming)
