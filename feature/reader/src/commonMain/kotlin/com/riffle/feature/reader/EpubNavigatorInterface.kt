package com.riffle.feature.reader

import kotlinx.coroutines.flow.Flow

/**
 * Platform-agnostic seam for the EPUB rendering backend. Hides all Readium (Android) or
 * Readium Swift (iOS) types from the ViewModel and coordinators.
 *
 * Android implementation: [AndroidEpubNavigator] (wraps [ReadiumPresenter] +
 * [EpubNavigatorFragment]). iOS implementation: ReadiumSwiftNavigator (issue #869).
 *
 * Currency: [LocatorJson] (= String) — Readium Locator serialised to JSON. Adapters convert
 * at the platform boundary; commonMain code never touches Readium types.
 *
 * Decoration groups: callers identify decoration groups by string key (e.g.
 * `"highlights"`, `"search"`, `"bookmarks"`). The navigator replaces the entire group
 * atomically on each [applyDecorations] call.
 */
interface EpubNavigatorInterface {

    // ── Book lifecycle ──────────────────────────────────────────────────────────

    /**
     * Open the EPUB at [bookFilePath] and seek to [initialLocatorJson] if provided.
     * The implementation opens the publication, attaches the rendering backend, and begins
     * emitting [positionFlow] events. Suspends until the navigator is ready to accept commands.
     */
    suspend fun open(bookFilePath: String, initialLocatorJson: LocatorJson?)

    /** Release all resources and detach the rendering backend. Safe to call multiple times. */
    fun close()

    // ── Position and layout events ──────────────────────────────────────────────

    /** Emits the current reading position whenever it changes. */
    val positionFlow: Flow<NavigatorPosition>

    /**
     * Bumps every time the renderer finishes loading a chapter and layout has settled.
     * Consumers re-apply decorations on each emission.
     */
    val pageLoadEvents: Flow<NavigatorPageLoad>

    /**
     * Per-chapter `viewportSize / chapterSize` fractions. Key = normalised chapter href;
     * value in [0.0, 1.0]. Must NOT emit on every scroll frame — only on measure/load.
     */
    val viewportFractionEvents: Flow<Pair<String, Double>>

    // ── User interaction events ─────────────────────────────────────────────────

    /** All user interactions that the ViewModel needs to respond to. */
    val eventFlow: Flow<NavigatorEvent>

    // ── Navigation commands ─────────────────────────────────────────────────────

    /** Navigate to [target] with the given [options]. */
    suspend fun navigateTo(
        target: NavigatorNavigationTarget,
        options: NavigatorNavigationOptions = NavigatorNavigationOptions(),
    )

    /** Turn forward or backward by one page (volume keys, configurable gestures). */
    suspend fun pageBy(direction: NavigatorPageDirection)

    // ── Visual commands ─────────────────────────────────────────────────────────

    /** Replace all decorations in [group] with [decorations]. Pass empty list to clear. */
    fun applyDecorations(group: String, decorations: List<NavigatorDecoration>)

    /**
     * Apply a highlights DOM patch (from [HighlightsDomPatch]) to the live chapter WebView.
     * [patchJson] is the serialised patch payload understood by the JS side.
     */
    suspend fun applyHighlightDomPatch(patchJson: String)

    // ── Readaloud and Cadence sentence follow ───────────────────────────────────

    /**
     * Locate the narrated [text] on the current page and snap to its column (paginated only).
     * Vertical/continuous return [NavigatorFollowResult.Unavailable].
     */
    suspend fun followReadaloudSentence(text: String): NavigatorFollowResult

    suspend fun followCadenceSpan(fragmentId: String): NavigatorFollowResult

    suspend fun measureReadaloudColumns(text: String): List<Double>

    suspend fun snapReadaloudColumn(text: String, columnIndex: Int)

    suspend fun measureCadenceColumns(fragmentId: String): List<Double>

    suspend fun snapCadenceColumn(fragmentId: String, columnIndex: Int)

    // ── Search ──────────────────────────────────────────────────────────────────

    /**
     * Execute a full-text search over the publication. Returns a [Flow] of match batches
     * emitted as the search progresses; collect until the flow completes for full results.
     */
    suspend fun search(query: String): Flow<List<NavigatorSearchMatch>>

    // ── Misc ────────────────────────────────────────────────────────────────────

    /** Snapshot of the last reported position; null before first [positionFlow] emission. */
    fun snapshotPosition(): NavigatorPosition?

    /** Fetch the raw bytes for a chapter resource — used by the continuous reader's WebView. */
    suspend fun getChapterBytes(href: String): ByteArray?

    /** Current scroll boundary state. Paginated mode always returns [NavigatorScrollBoundary.None]. */
    suspend fun scrollBoundary(): NavigatorScrollBoundary
}
