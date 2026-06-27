package com.riffle.app.feature.reader.presenter

import com.riffle.core.domain.FormattingPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Test double that lets view-model and orchestrator tests drive features through the
 * [ReaderPresenter] contract without booting Readium or a WebView.
 *
 * Tests push events with the `emit*` helpers; the presenter records every command in the
 * `recorded*` lists. The contract is intentionally permissive — implementations may emit any
 * subset of events, in any order. Tests assert on what they emit, not on what a real renderer
 * would.
 */
internal class FakeReaderPresenter : ReaderPresenter {

    private val _positionEvents = MutableSharedFlow<PositionUpdate>(replay = 0, extraBufferCapacity = 64)
    private val _pageLoadEvents = MutableSharedFlow<PageLoadGeneration>(replay = 0, extraBufferCapacity = 64)
    private val _tapEvents = MutableSharedFlow<TapEvent>(replay = 0, extraBufferCapacity = 64)
    private val _linkEvents = MutableSharedFlow<LinkEvent>(replay = 0, extraBufferCapacity = 64)
    private val _selectionEvents = MutableSharedFlow<SelectionEvent>(replay = 0, extraBufferCapacity = 64)
    private val _annotationTapEvents = MutableSharedFlow<AnnotationTapEvent>(replay = 0, extraBufferCapacity = 64)

    override val positionEvents: SharedFlow<PositionUpdate> = _positionEvents
    override val pageLoadEvents: SharedFlow<PageLoadGeneration> = _pageLoadEvents
    override val tapEvents: SharedFlow<TapEvent> = _tapEvents
    override val linkEvents: SharedFlow<LinkEvent> = _linkEvents
    override val selectionEvents: SharedFlow<SelectionEvent> = _selectionEvents
    override val annotationTapEvents: SharedFlow<AnnotationTapEvent> = _annotationTapEvents

    val recordedNavigations: MutableList<NavigationTarget> = mutableListOf()
    val recordedTypography: MutableList<FormattingPreferences> = mutableListOf()
    val recordedPagesBy: MutableList<PageDirection> = mutableListOf()

    private var lastPosition: ReaderPosition? = null
    private var generation: Long = 0L

    override suspend fun navigateTo(target: NavigationTarget) {
        recordedNavigations += target
    }

    override suspend fun applyTypography(prefs: FormattingPreferences) {
        recordedTypography += prefs
    }

    override fun snapshotPosition(): ReaderPosition? = lastPosition

    override suspend fun pageBy(direction: PageDirection) {
        recordedPagesBy += direction
    }

    // ----- Event drivers (for tests) -------------------------------------------------------

    suspend fun emitPosition(position: ReaderPosition) {
        lastPosition = position
        generation += 1
        _positionEvents.emit(PositionUpdate(position, generation))
    }

    suspend fun emitPageLoad(value: Int) {
        _pageLoadEvents.emit(PageLoadGeneration(value))
    }

    suspend fun emitTap(event: TapEvent = TapEvent.Body) {
        _tapEvents.emit(event)
    }

    suspend fun emitLink(event: LinkEvent) {
        _linkEvents.emit(event)
    }

    suspend fun emitSelection(event: SelectionEvent) {
        _selectionEvents.emit(event)
    }

    suspend fun emitAnnotationTap(event: AnnotationTapEvent) {
        _annotationTapEvents.emit(event)
    }
}
