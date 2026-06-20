package com.riffle.app.feature.reader

/**
 * Narrow interface over [ContinuousReaderView]'s navigation method.
 * Extracted so [ContinuousNavigationTarget] can be tested on JVM without
 * instantiating the Android View.
 */
internal interface ContinuousNavigationView {
    fun navigateTo(href: String, progression: Float, alignToTop: Boolean = false)
}
