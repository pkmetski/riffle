package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences

/**
 * Narrow interface over [ContinuousReaderView] for the
 * [com.riffle.app.feature.reader.presenter.ContinuousPresenter] (and the legacy
 * [ContinuousNavigationTarget]). Extracted so the presenter can be JVM-tested without
 * instantiating the Android View. Keep it minimal — only what the presenter actually needs.
 */
internal interface ContinuousNavigationView {
    fun navigateTo(href: String, progression: Float, alignToTop: Boolean = false)

    /** Page forward / backward; same semantics as [ContinuousReaderView.scrollByPage]. */
    fun scrollByPage(forward: Boolean)

    /** Live-update typography for the running continuous session. */
    fun updatePreferences(prefs: FormattingPreferences)
}
