package com.riffle.shared.library

import com.riffle.core.data.AnnotatedBook
import com.riffle.core.data.AnnotationsLibraryRepository
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.AudiobookBookmarkStore
import com.riffle.core.domain.CoverGridDensityStore
import com.riffle.core.domain.LibraryFilterPreferences
import com.riffle.core.domain.LibraryFilterPreferencesStore
import com.riffle.core.domain.LibraryItemOfflineAvailability
import com.riffle.core.domain.ReadaloudLinkReconciler
import com.riffle.core.domain.ReadaloudLinkRepository
import com.riffle.core.domain.StorytellerReadaloudCacheSyncer
import com.riffle.core.models.Annotation
import com.riffle.core.models.AudiobookBookmark
import com.riffle.core.models.AudiobookIdentityResult
import com.riffle.core.models.EmbeddedFigure
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.ReadaloudLink
import com.riffle.core.models.ScreenDimensionBucket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

internal class IosNoOpLibraryItemOfflineAvailability : LibraryItemOfflineAvailability {
    override fun isAvailableOffline(item: LibraryItem): Boolean = false
}

internal object IosNoOpStorytellerSyncer : StorytellerReadaloudCacheSyncer {
    override suspend fun syncStale() {}
}

internal object IosNoOpReadaloudReconciler : ReadaloudLinkReconciler {
    override suspend fun reconcileLinks() {}
}

internal object IosNoOpApplicationScope : ApplicationScope {
    private val supervisor = SupervisorJob()
    override val coroutineScope: CoroutineScope = CoroutineScope(supervisor)
    override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job =
        coroutineScope.launch(block = block)
    override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T =
        block(coroutineScope)
    override fun scopeOn(dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(supervisor + dispatcher)
}

internal class IosNoOpCoverGridDensityStore : CoverGridDensityStore {
    override val scale: Flow<Float> = flowOf(1f)
    override suspend fun setScale(value: Float) {}
    override fun scale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket): Flow<Float> = flowOf(1f)
    override suspend fun setScale(sourceId: String, libraryId: String, bucket: ScreenDimensionBucket, value: Float) {}
}

internal class IosNoOpLibraryFilterPreferencesStore : LibraryFilterPreferencesStore {
    override fun preferences(sourceId: String, libraryId: String): Flow<LibraryFilterPreferences> =
        flowOf(LibraryFilterPreferences())
    override suspend fun setSelectedFacetKey(sourceId: String, libraryId: String, key: String?) {}
    override suspend fun setNotStartedFilterActive(sourceId: String, libraryId: String, active: Boolean) {}
    override suspend fun setUnownedFilterActive(sourceId: String, libraryId: String, active: Boolean) {}
    override suspend fun setSortModeName(sourceId: String, libraryId: String, name: String?) {}
}

internal class IosNoOpAnnotationStore : AnnotationStore {
    override fun observeHighlights(sourceId: String, itemId: String): Flow<List<Annotation>> = flowOf(emptyList())
    override fun observeBookmarks(sourceId: String, itemId: String): Flow<List<Annotation>> = flowOf(emptyList())
    override fun observeAnnotations(sourceId: String, itemId: String): Flow<List<Annotation>> = flowOf(emptyList())
    override fun observeAnnotationsForSource(sourceId: String): Flow<List<Annotation>> = flowOf(emptyList())
    override fun observeEmphasis(sourceId: String, itemId: String): Flow<List<Annotation>> = emptyFlow()
    override suspend fun createHighlight(
        sourceId: String, itemId: String, cfi: String, textSnippet: String,
        chapterHref: String, textBefore: String, textAfter: String, color: String,
        spineIndex: Int, progression: Double, embeddedFigures: List<EmbeddedFigure>?,
        originFontFamily: String, textSnippetHtml: String?,
    ): Annotation = error("Not implemented on iOS")
    override suspend fun createBookmark(
        sourceId: String, itemId: String, cfi: String, textSnippet: String,
        chapterHref: String, spineIndex: Int, progression: Double, bookmarkTitle: String,
        originFontFamily: String, fragmentAnchor: String?,
    ): Annotation = error("Not implemented on iOS")
    override suspend fun createImageAnnotation(
        sourceId: String, itemId: String, cfi: String, textSnippet: String,
        chapterHref: String, spineIndex: Int, progression: Double,
        imageHref: String?, imageSvg: String?, imageBytes: String?, color: String,
    ): Annotation = error("Not implemented on iOS")
    override suspend fun backfillNullOriginFontFamily(sourceId: String, itemId: String, fontFamily: String): Int = 0
    override suspend fun healSentinelOriginFontFamily(sourceId: String, itemId: String, sentinel: String, fontFamily: String): Int = 0
    override suspend fun upgradeImageToCaptionHighlight(
        id: String, cfi: String, textSnippet: String, textBefore: String, textAfter: String, figure: EmbeddedFigure,
    ): Annotation? = null
    override suspend fun mergeFiguresIntoHighlight(id: String, newFigures: List<EmbeddedFigure>): Annotation? = null
    override suspend fun delete(id: String) {}
    override suspend fun recolor(id: String, color: String) {}
    override suspend fun updateNote(id: String, note: String?) {}
    override suspend fun renameBookmark(id: String, title: String) {}
    override suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): Annotation? = null
    override suspend fun findImageAnnotationForFigure(
        sourceId: String, itemId: String, chapterHref: String, imageHref: String?, imageSvg: String?,
    ): Annotation? = null
}

internal class IosNoOpAudiobookBookmarkStore : AudiobookBookmarkStore {
    override fun observe(sourceId: String, itemId: String): Flow<List<AudiobookBookmark>> = flowOf(emptyList())
    override fun observeForSource(sourceId: String): Flow<List<AudiobookBookmark>> = flowOf(emptyList())
    override fun observeHasUnsynced(sourceId: String, itemId: String): Flow<Boolean> = flowOf(false)
    override suspend fun add(sourceId: String, itemId: String, positionSec: Double, title: String, now: Long): String = ""
    override suspend fun rename(id: String, title: String, now: Long) {}
    override suspend fun delete(id: String, now: Long) {}
}

internal class IosNoOpReadaloudLinkRepository : ReadaloudLinkRepository {
    override fun observeAll(): Flow<List<ReadaloudLink>> = flowOf(emptyList())
    override fun observeLinkedAbsItemIds(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun findByAbsItem(absSourceId: String, absLibraryItemId: String): ReadaloudLink? = null
    override suspend fun findByStorytellerBook(storytellerSourceId: String, storytellerBookId: String): List<ReadaloudLink> = emptyList()
    override suspend fun unlinkAbsItem(absSourceId: String, absLibraryItemId: String) {}
    override suspend fun countForSource(sourceId: String): Int = 0
    override suspend fun updateIdentityResult(absSourceId: String, absLibraryItemId: String, result: AudiobookIdentityResult) {}
}

internal class IosNoOpAnnotationsLibraryRepository : AnnotationsLibraryRepository {
    override fun observeAnnotatedBooks(sourceId: String): Flow<List<AnnotatedBook>> = flowOf(emptyList())
    override fun observeAnnotatedBooks(sourceId: String, libraryId: String): Flow<List<AnnotatedBook>> = flowOf(emptyList())
}
