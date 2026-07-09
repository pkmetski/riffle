package com.riffle.app.feature.reader

import com.riffle.core.domain.FormattingPreferences

/**
 * Narrow interface over [ContinuousReaderView] for the
 * [com.riffle.app.feature.reader.presenter.ContinuousPresenter]. Extracted so the presenter can be
 * JVM-tested without instantiating the Android View. Keep it minimal — only what the presenter
 * actually needs.
 */
internal interface ContinuousNavigationView {
    fun navigateTo(href: String, progression: Float, alignToTop: Boolean = false)

    /**
     * Annotation-precise navigate: when [focusAnnotationId] is non-null the landing anchors on
     * the actual `<mark data-riffle-ann="…">` device-Y instead of the enclosing paragraph's top,
     * so a mid- or end-paragraph highlight lands visibly on-screen rather than pushed below.
     * Default forwards to the plain three-arg overload for callers that don't have an id.
     */
    fun navigateTo(href: String, progression: Float, alignToTop: Boolean, focusAnnotationId: String?) {
        navigateTo(href, progression, alignToTop)
    }

    /** Page forward / backward; same semantics as [ContinuousReaderView.scrollByPage]. */
    fun scrollByPage(forward: Boolean)

    /** Live-update typography for the running continuous session. */
    fun updatePreferences(prefs: FormattingPreferences)

    /**
     * Apply one [com.riffle.app.feature.reader.highlights.HighlightsDomPatch] (ADR 0041) to every
     * currently loaded chapter WebView in the sliding window. Each patch's JS looks up its target
     * by `data-ann-id` and no-ops on chapters that don't contain it, so a fan-out broadcast is
     * safe and works regardless of which chapter is scrolled into view. Default is a no-op so
     * lightweight fakes for the presenter's JVM tests need not override.
     */
    fun applyHighlightDomPatch(patch: com.riffle.app.feature.reader.highlights.HighlightsDomPatch) {}
}
