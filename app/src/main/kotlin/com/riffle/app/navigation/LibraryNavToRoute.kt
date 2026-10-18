package com.riffle.app.navigation

import androidx.navigation.NavController
import com.riffle.feature.library.urlFormEncode
import com.riffle.feature.navigation.LibraryNav
import com.riffle.feature.navigation.NavigationIntent
import com.riffle.feature.navigation.reduceNavStack

/**
 * Translates a [LibraryNav] destination to the Android NavController route string used by the
 * NavHost. Returns `null` for destinations that the NavHost does not navigate to via a
 * route string (e.g. [LibraryNav.Items], [LibraryNav.Section], or iOS-only loaders).
 */
internal fun libraryNavToAndroidRoute(nav: LibraryNav): String? = when (nav) {
    is LibraryNav.Reader -> buildString {
        append("epub_reader/")
        append(nav.item.id.urlFormEncode())
        val params = buildList {
            if (nav.item.sourceId.isNotBlank()) add("sourceId=${nav.item.sourceId.urlFormEncode()}")
            nav.startHref?.let { add("startTocHref=${it.urlFormEncode()}") }
            nav.initialLocatorJson?.let { add("openAtCfi=${it.urlFormEncode()}") }
            if (nav.startReadaloudAtSec >= 0) add("startReadaloudAtSec=${nav.startReadaloudAtSec}")
        }
        if (params.isNotEmpty()) append("?${params.joinToString("&")}")
    }
    is LibraryNav.PdfReader -> {
        val sourceParam = if (nav.item.sourceId.isNotBlank()) "?sourceId=${nav.item.sourceId.urlFormEncode()}" else ""
        "pdf_reader/${nav.item.id.urlFormEncode()}$sourceParam"
    }
    is LibraryNav.CbzReader -> {
        val sourceParam = if (nav.item.sourceId.isNotBlank()) "?sourceId=${nav.item.sourceId.urlFormEncode()}" else ""
        "cbz_reader/${nav.item.id.urlFormEncode()}$sourceParam"
    }
    is LibraryNav.AudiobookPlayer -> buildString {
        append("audiobook_player/")
        append(nav.item.sourceId.urlFormEncode())
        append("/")
        append(nav.item.id.urlFormEncode())
        val params = buildList {
            if (nav.startAtSec >= 0) add("startAtSec=${nav.startAtSec}")
            nav.playlistId?.let { add("playlistId=${it.urlFormEncode()}") }
            nav.playlistLibraryId?.let { add("libraryId=${it.urlFormEncode()}") }
        }
        if (params.isNotEmpty()) append("?${params.joinToString("&")}")
    }
    is LibraryNav.ElidedReader -> {
        val encodedSourceId = nav.sourceId?.urlFormEncode().orEmpty()
        "epub_reader/${nav.itemId.urlFormEncode()}?source=highlights&sourceId=$encodedSourceId"
    }
    is LibraryNav.AudiobookBookmark -> {
        val encodedSourceId = nav.sourceId?.urlFormEncode().orEmpty()
        "audiobook_player/$encodedSourceId/${nav.itemId.urlFormEncode()}?startAtSec=${nav.startAtSec}"
    }
    else -> null
}

/**
 * Dispatches a [NavigationIntent] to the [NavController] by computing the target destination
 * through [reduceNavStack] and translating it to an Android route string.
 *
 * Returns `true` if navigation was performed, `false` if the intent produced no navigable route
 * (e.g. the item format is unsupported). The current stack is passed as empty because the
 * Android NavController manages its own back stack; deduplication is handled by the NavHost's
 * `launchSingleTop` flag on destinations where it applies.
 */
internal fun NavController.navigateForIntent(intent: NavigationIntent): Boolean {
    val dest = reduceNavStack(intent, emptyList()).lastOrNull() ?: return false
    val route = libraryNavToAndroidRoute(dest) ?: return false
    navigate(route)
    return true
}
