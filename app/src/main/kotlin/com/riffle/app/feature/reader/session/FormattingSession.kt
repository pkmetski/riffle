package com.riffle.app.feature.reader.session

import com.riffle.app.feature.reader.TimeProvider
import com.riffle.app.feature.reader.autoscroll.AutoScrollController
import com.riffle.core.domain.BookFormattingOverrides
import com.riffle.core.domain.BookFormattingPreferencesStore
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.FormattingPreferencesStore
import com.riffle.core.domain.ListeningPreferencesStore
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.WakeLockPreferencesStore
import com.riffle.core.domain.autoscroll.AutoScrollEvent
import com.riffle.core.domain.autoscroll.AutoScrollSpeed
import com.riffle.core.domain.autoscroll.AutoScrollState
import com.riffle.core.domain.autoscroll.LayoutContext
import com.riffle.core.domain.withResolvedTheme
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
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
    private val timeProvider: TimeProvider,
    private val formattingPreferencesStore: FormattingPreferencesStore,
    private val bookFormattingPreferencesStore: BookFormattingPreferencesStore,
    private val wakeLockPreferencesStore: WakeLockPreferencesStore,
    private val listeningPreferencesStore: ListeningPreferencesStore,
    private val autoScrollController: AutoScrollController,
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

    // Ticks emitted at each ThemeSchedule boundary so the resolved theme can flip live
    // during a reading session. Re-armed whenever the schedule changes.
    private val scheduleTicks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, replay = 1).apply {
        tryEmit(Unit) // prime the combine() below so it emits immediately on collection
    }

    // The user's pick with `theme` replaced by the resolver's concrete value when the
    // pick is Auto. Every downstream consumer (Readium navigator submitPreferences,
    // chapter rail backdrop, palette) reads this — they stay ignorant of Auto.
    val effectiveFormattingPreferences: StateFlow<FormattingPreferences> = combine(
        _formattingPreferences,
        scheduleTicks,
    ) { prefs, _ -> prefs.withResolvedTheme(timeProvider.nowLocalTime()) }
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
    private var themeScheduleJob: Job? = null

    // Device pixel density for accurate layout context. Default = 1f (CSS-px fallback).
    // The VM sets this via [setDeviceDensity] in its init block, after constructing the session,
    // using application.resources.displayMetrics.density.
    @Volatile private var deviceDensity: Float = 1f

    /**
     * Provides the device pixel density so the Auto-Scroll layout context produces correct
     * device-pixel line heights. Call once from the VM init block.
     */
    fun setDeviceDensity(density: Float) {
        deviceDensity = density
    }

    init {
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
        // Auto-Scroll layout context (line height in device pixels) follows the effective font
        // size. Without this the px-per-second pace defaults to a CSS-px estimate and ends up
        // ~3× too slow on a typical xxhdpi screen. Body text ~22 CSS px line height at default
        // font size; scaled by user font multiplier and the device density.
        scope.launch {
            _formattingPreferences
                .map { it.fontSize }
                .distinctUntilChanged()
                .collect { fontSize ->
                    autoScrollController.setLayoutContext {
                        LayoutContext(
                            wordsPerLine = 9f,
                            lineHeightPx = 22f * fontSize * deviceDensity,
                        )
                    }
                }
        }
        // Readaloud start ⇒ stop Auto-Scroll (mutual exclusion, ADR 0037).
        // Driven externally via onPlaybackStateChanged(isPlaying).
        armThemeSchedule()
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
            // Wait until the derived StateFlow actually reflects the loaded value.
            val targetEffective = effective.withResolvedTheme(timeProvider.nowLocalTime())
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

    /** Called when the book is closed. Cancels any in-flight theme schedule loop. */
    fun onBookClosed() {
        themeScheduleJob?.cancel()
        themeScheduleJob = null
        _formattingPreferencesReady.value = false
    }

    // ---- Private -------------------------------------------------------------------------------

    private fun armThemeSchedule() {
        themeScheduleJob?.cancel()
        themeScheduleJob = scope.launch {
            _formattingPreferences
                .map { it.themeSchedule to (it.theme == ReaderTheme.Auto) }
                .distinctUntilChanged()
                .collectLatest { (schedule, autoActive) ->
                    if (!autoActive) return@collectLatest
                    // Degenerate schedule (equal day/night times) collapses to always-day —
                    // no boundary will ever arrive. Park until cancelled.
                    if (schedule.dayStart == schedule.nightStart) {
                        awaitCancellation()
                    }
                    while (true) {
                        val now = timeProvider.nowLocalTime()
                        val next = schedule.nextBoundaryAfter(now)
                        val nowSec = now.toSecondOfDay().toLong()
                        val nextSec = next.toSecondOfDay().toLong()
                        val delayMs = (((nextSec - nowSec + 24 * 3600) % (24 * 3600)) * 1000L)
                            .coerceAtLeast(1_000L)
                        delay(delayMs)
                        scheduleTicks.tryEmit(Unit)
                    }
                }
        }
    }
}
