package com.riffle.app.feature.reader

import com.riffle.feature.reader.EpubNavigatorInterface
import com.riffle.feature.reader.LocatorJson
import com.riffle.feature.reader.NavigatorDecoration
import com.riffle.feature.reader.NavigatorEvent
import com.riffle.feature.reader.NavigatorFollowResult
import com.riffle.feature.reader.NavigatorNavigationOptions
import com.riffle.feature.reader.NavigatorNavigationTarget
import com.riffle.feature.reader.NavigatorPageDirection
import com.riffle.feature.reader.NavigatorPageLoad
import com.riffle.feature.reader.NavigatorPosition
import com.riffle.feature.reader.NavigatorScrollBoundary
import com.riffle.feature.reader.NavigatorSearchMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow

/** Test double for [EpubNavigatorInterface]. */
class FakeEpubNavigator : EpubNavigatorInterface {

    val fakePositionFlow = MutableSharedFlow<NavigatorPosition>(replay = 1)
    val fakePageLoadEvents = MutableSharedFlow<NavigatorPageLoad>(replay = 0)
    val fakeViewportFractionEvents = MutableSharedFlow<Pair<String, Double>>(replay = 0)
    val fakeEventFlow = MutableSharedFlow<NavigatorEvent>(replay = 0)

    override val positionFlow: Flow<NavigatorPosition> = fakePositionFlow
    override val pageLoadEvents: Flow<NavigatorPageLoad> = fakePageLoadEvents
    override val viewportFractionEvents: Flow<Pair<String, Double>> = fakeViewportFractionEvents
    override val eventFlow: Flow<NavigatorEvent> = fakeEventFlow

    var openedPath: String? = null
    var openedLocatorJson: LocatorJson? = null
    var closed = false
    val navigateCalls = mutableListOf<Pair<NavigatorNavigationTarget, NavigatorNavigationOptions>>()
    val pageByDirections = mutableListOf<NavigatorPageDirection>()
    val decorationsApplied = mutableListOf<Pair<String, List<NavigatorDecoration>>>()

    override suspend fun open(bookFilePath: String, initialLocatorJson: LocatorJson?) {
        openedPath = bookFilePath
        openedLocatorJson = initialLocatorJson
    }

    override fun close() {
        closed = true
    }

    override suspend fun navigateTo(
        target: NavigatorNavigationTarget,
        options: NavigatorNavigationOptions,
    ) {
        navigateCalls += target to options
    }

    override suspend fun pageBy(direction: NavigatorPageDirection) {
        pageByDirections += direction
    }

    override fun applyDecorations(group: String, decorations: List<NavigatorDecoration>) {
        decorationsApplied += group to decorations
    }

    override suspend fun applyHighlightDomPatch(patchJson: String) {}

    override suspend fun followReadaloudSentence(text: String) = NavigatorFollowResult.Unavailable

    override suspend fun followCadenceSpan(fragmentId: String) = NavigatorFollowResult.Unavailable

    override suspend fun measureReadaloudColumns(text: String): List<Double> = emptyList()

    override suspend fun snapReadaloudColumn(text: String, columnIndex: Int) {}

    override suspend fun measureCadenceColumns(fragmentId: String): List<Double> = emptyList()

    override suspend fun snapCadenceColumn(fragmentId: String, columnIndex: Int) {}

    override suspend fun search(query: String): Flow<List<NavigatorSearchMatch>> = emptyFlow()

    override fun snapshotPosition(): NavigatorPosition? = null

    override suspend fun getChapterBytes(href: String): ByteArray? = null

    override suspend fun scrollBoundary(): NavigatorScrollBoundary = NavigatorScrollBoundary.None
}
