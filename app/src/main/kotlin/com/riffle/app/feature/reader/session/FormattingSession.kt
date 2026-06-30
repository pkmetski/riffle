package com.riffle.app.feature.reader.session

import com.riffle.app.feature.reader.autoscroll.AutoScrollController
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.appearance.AppearanceCoordinator
import com.riffle.core.domain.autoscroll.AutoScrollEvent
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.domain.autoscroll.layoutContextFor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns all formatting/typography/auto-scroll state for a single open book. Lifted from
 * EpubReaderViewModel as part of the VM split (#303). The VM injects the [Factory] and calls
 * [Factory.create] in its `init` block, passing `viewModelScope` so lifecycle is deterministic.
 *
 * MUST NOT import org.readium.*, android.webkit.*, or ContinuousReaderView.
 */
class FormattingSession @AssistedInject constructor(
    @Assisted private val scope: OrchestratorScope,
    private val formattingPreferencesStore: FormattingPreferencesStore,
    private val bookFormattingPreferencesStore: BookFormattingPreferencesStore,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    private val listeningPreferencesStore: ListeningPreferencesStore,
    private val autoScrollController: AutoScrollController,
    private val appearanceCoordinator: AppearanceCoordinator,
) {

    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): FormattingSession
    }

    // Per-field book overrides. null on a field means "follow global", so changing global later
    // propagates to the book for fields the user hasn't touched in the panel.
    private val _bookOverrides = MutableStateFlow(BookFormattingOverrides())

    // Effective prefs = global ⊕ overrides. Updated optimistically on user change so the
    // navigator sees the new value without waiting for the Room write to complete.
    private val _formattingPreferences = MutableStateFlow(FormattingPreferences())
    // The raw user-picked prefs without theme resolution — needed by the screen's
    // formattingPrefsProvider so it can synchronously read the orientation on the
    // startup fast-path (before effectiveFormattingPreferences's combine→stateIn hop).
    val formattingPreferences: StateFlow<FormattingPreferences> = _formattingPreferences

    // The user's pick with `theme` replaced by the schedule-resolved concrete value when the
    // pick is Auto. Every downstream consumer (Readium navigator submitPreferences,
    // chapter rail backdrop, palette) reads this — they stay ignorant of Auto. The
    // boundary-tick that flips Auto live mid-session is owned by AppearanceCoordinator;
    // its `resolved` StateFlow re-emits at each day/night crossing, which propagates
    // through this combine and drives Readium reapplication.
    val effectiveFormattingPreferences: StateFlow<FormattingPreferences> = combine(
        _formattingPreferences,
        appearanceCoordinator.resolved,
    ) { prefs, appearance ->
        if (prefs.theme == ReaderTheme.Auto) prefs.copy(theme = appearance.readerTheme.toReaderTheme())
        else prefs
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, FormattingPreferences())

    private val _hasBookOverrides = MutableStateFlow(false)
    val hasBookOverrides: StateFlow<Boolean> = _hasBookOverrides

    // False until loadFormattingPreferences() has both written the loaded value into
    // _formattingPreferences AND that value has propagated through the combine→stateIn chain
    // backing effectiveFormattingPreferences. The screen gates EpubNavigatorFragment /
    // ContinuousReaderView construction on this so the first paint never uses the StateFlow's
    // FormattingPreferences() default.
    private val _formattingPreferencesReady = MutableStateFlow(false)
    val formattingPreferencesReady: StateFlow<Boolean> = _formattingPreferencesReady

    val autoScrollState: StateFlow<AutoScrollState> = autoScrollController.state

    val autoScrollScrollDeltas: Flow<Int> = autoScrollController.scrollDeltas

    private var bookId: String? = null

    // Device pixel density for accurate layout context. Default = 1f (CSS-px fallback).
    // The VM sets this via [setDeviceDensity] in its init block, after constructing the session,
    // using application.resources.displayMetrics.density.
    @Volatile private var deviceDensity: Float = 1f

    // Reader viewport width in device pixels. The screen pushes it via [setViewportWidthPx]
    // whenever the configuration width or density changes (orientation, foldable resize).
    // Until set, wordsPerLine collapses to zero and pxPerSecond returns zero, so the controller
    // simply doesn't advance — safer than guessing a width.
    @Volatile private var viewportWidthPx: Int = 0

    /**
     * Provides the device pixel density so the Auto-Scroll layout context produces correct
     * device-pixel line heights. Call once from the VM init block.
     */
    fun setDeviceDensity(density: Float) {
        deviceDensity = density
    }

    /**
     * Provides the reader viewport width in device pixels so the Auto-Scroll layout context can
     * compute words-per-line from the actual on-screen column. Call whenever the configuration
     * width or density changes.
     */
    fun setViewportWidthPx(px: Int) {
        viewportWidthPx = px
    }

    init {
        // The AutoScrollController is a process-wide Singleton. Defensively reset to Idle on every
        // book open so a session that wasn't cleanly torn down (process kill mid-scroll, then
        // restart into a different book) does not auto-start the new book mid-air.
        autoScrollController.dispatch(AutoScrollEvent.Stop)
        // Keep effective prefs in sync with both global changes and override updates.
        scope.launch {
            combine(
                formattingPreferencesStore.preferences,
                _bookOverrides,
            ) { global, overrides -> overrides.applyTo(global) to !overrides.isEmpty }
                .collect { (effective, hasOverrides) ->
                    _formattingPreferences.value = effective
                    _hasBookOverrides.value = hasOverrides
                }
        }
        // Auto-Scroll defaultSpeed follows the effective FormattingPreferences (global + override).
        scope.launch {
            _formattingPreferences
                .map { it.autoScrollWpm }
                .distinctUntilChanged()
                .collect { wpm ->
                    autoScrollController.setDefaultSpeed(
                        AutoScrollSpeed.of(wpm),
                    )
                }
        }
        // Auto-Scroll layout context is recomputed lazily on every tick from the live formatting
        // preferences (font size, line spacing, margins, font family) and the latest viewport.
        // The supplier captures `this`, so updates to `_formattingPreferences`, `deviceDensity`,
        // and `viewportWidthPx` flow through without re-installing it.
        autoScrollController.setLayoutContext {
            layoutContextFor(
                prefs = _formattingPreferences.value,
                viewportWidthPx = viewportWidthPx,
                deviceDensity = deviceDensity,
            )
        }
        // Readaloud start ⇒ stop Auto-Scroll (mutual exclusion, ADR 0037).
        // Driven externally via onPlaybackStateChanged(isPlaying).
    }

    /**
     * Load book-specific overrides and mark prefs as ready. Must be called once per book open,
     * before [effectiveFormattingPreferences] is consumed by the navigator.
     */
    fun bindToBook(itemId: String) {
        bookId = itemId
        scope.launch {
            val overrides = bookFormattingPreferencesStore.load(itemId)
            val global = formattingPreferencesStore.preferences.first()
            _bookOverrides.value = overrides
            val effective = overrides.applyTo(global)
            _formattingPreferences.value = effective
            _hasBookOverrides.value = !overrides.isEmpty
            // Wait until the derived StateFlow actually reflects the loaded value. Mirrors the
            // combine() above: Auto resolves to the coordinator's current concrete theme.
            val resolvedReader = appearanceCoordinator.resolved.value.readerTheme.toReaderTheme()
            val targetEffective = if (effective.theme == ReaderTheme.Auto) {
                effective.copy(theme = resolvedReader)
            } else effective
            effectiveFormattingPreferences.first { it == targetEffective }
            _formattingPreferencesReady.value = true
        }
    }

    /**
     * Persist a user-driven formatting change, optimistically updating in-memory state so the
     * navigator sees the new value without waiting for the Room write.
     */
    fun updateFormatting(itemId: String, prefs: FormattingPreferences) {
        val previousEffective = _formattingPreferences.value
        val updated = _bookOverrides.value.withChanges(previousEffective, prefs)
        _bookOverrides.value = updated
        _formattingPreferences.value = prefs
        _hasBookOverrides.value = !updated.isEmpty
        scope.launch { bookFormattingPreferencesStore.save(itemId, updated) }
    }

    /** Clear all book-level overrides, reverting to the global formatting preferences. */
    fun resetToGlobalDefaults(itemId: String) {
        scope.launch {
            bookFormattingPreferencesStore.clear(itemId)
            _bookOverrides.value = BookFormattingOverrides()
            _formattingPreferences.value = formattingPreferencesStore.preferences.first()
            _hasBookOverrides.value = false
        }
    }

    // ---- Auto-Scroll passthrough ----------------------------------------------------------------

    fun setAutoScrollPaused(paused: Boolean, cause: com.riffle.core.domain.autoscroll.PauseCause) {
        if (paused) {
            autoScrollController.dispatch(AutoScrollEvent.Pause(cause))
        } else {
            autoScrollController.dispatch(AutoScrollEvent.Resume)
        }
    }

    fun startAutoScroll() {
        autoScrollController.dispatch(AutoScrollEvent.Start)
    }

    fun stopAutoScroll() {
        autoScrollController.dispatch(AutoScrollEvent.Stop)
    }

    fun nudgeAutoScroll(itemId: String, by: Int) {
        autoScrollController.dispatch(AutoScrollEvent.NudgeSpeed(by))
        val newSpeed = when (val s = autoScrollController.state.value) {
            is AutoScrollState.Running -> s.speed
            is AutoScrollState.Paused -> s.speed
            else -> null
        } ?: return
        val current = _formattingPreferences.value
        if (current.autoScrollWpm == newSpeed.wpm) return
        updateFormatting(itemId, current.copy(autoScrollWpm = newSpeed.wpm))
    }

    fun pauseAutoScroll(cause: com.riffle.core.domain.autoscroll.PauseCause) {
        autoScrollController.dispatch(AutoScrollEvent.Pause(cause))
    }

    fun resumeAutoScrollIfPaused() {
        autoScrollController.dispatch(AutoScrollEvent.Resume)
    }

    fun reachedEndOfBookForAutoScroll() {
        autoScrollController.dispatch(AutoScrollEvent.Stop)
    }

    /**
     * Called by the VM when the readaloud/audiobook playback state changes. If audio starts playing
     * and Auto-Scroll is running, stop it (mutual exclusion, ADR 0037).
     */
    fun onPlaybackStateChanged(isPlaying: Boolean) {
        if (isPlaying &&
            autoScrollController.state.value is AutoScrollState.Running
        ) {
            autoScrollController.dispatch(AutoScrollEvent.Stop)
        }
    }

    /** Called when the book is closed. */
    fun onBookClosed() {
        autoScrollController.dispatch(AutoScrollEvent.Stop)
        _formattingPreferencesReady.value = false
    }
}
