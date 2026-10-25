package com.riffle.shared.reader

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIReferenceLibraryViewController

/**
 * Thin wrappers for iOS system actions (web search, share sheet) triggered from the EPUB reader's
 * selection action strip.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosSystemActions {

    fun searchWeb(query: String) {
        val encoded = query.take(200).replace(" ", "+")
        val urlString = "https://www.google.com/search?q=$encoded"
        val url = NSURL.URLWithString(urlString) ?: return
        UIApplication.sharedApplication.openURL(url)
    }

    fun share(text: String) {
        val activity = UIActivityViewController(
            activityItems = listOf(text),
            applicationActivities = null,
        )
        UIApplication.sharedApplication.keyWindow?.rootViewController
            ?.presentViewController(activity, animated = true, completion = null)
    }

    fun lookUp(term: String) {
        val rootVc = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return
        if (!UIReferenceLibraryViewController.dictionaryHasDefinitionForTerm(term)) {
            searchWeb(term)
            return
        }
        rootVc.presentViewController(
            UIReferenceLibraryViewController(term),
            animated = true,
            completion = null,
        )
    }
}
