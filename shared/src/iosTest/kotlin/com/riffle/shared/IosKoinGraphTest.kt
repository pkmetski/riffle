package com.riffle.shared

import com.riffle.core.catalog.CatalogFactory
import com.riffle.core.catalog.CatalogRegistry
import com.riffle.core.catalog.abs.AbsCommonCatalogFactory
import com.riffle.core.domain.AppUpdatePreferencesStore
import com.riffle.core.domain.AppUpdateRepository
import com.riffle.core.domain.ApplicationScope
import com.riffle.core.domain.AudiobookCacheRepository
import com.riffle.core.domain.AudiobookChapterCacheRepository
import com.riffle.core.domain.AudiobookDownloadRepository
import com.riffle.core.domain.BundleAudiobookSource
import com.riffle.core.domain.CrashReportRepository
import com.riffle.core.domain.CrossEpubIndexBuildTrigger
import com.riffle.core.domain.CrossEpubIndexStore
import com.riffle.core.domain.EbookCfiTranslatorFactory
import com.riffle.core.domain.LocalAvailabilityEvents
import com.riffle.core.domain.PdfRepository
import com.riffle.core.domain.ReadaloudAudioRepository
import com.riffle.core.domain.ReadaloudBundleReader
import com.riffle.core.domain.ReadaloudLinkReconciler
import com.riffle.core.domain.ReadaloudReviewMutator
import com.riffle.core.domain.ReadaloudReviewRepository
import com.riffle.core.domain.ReadaloudSidecarDownloads
import com.riffle.core.domain.ReadaloudSidecarPrefetcher
import com.riffle.core.domain.StorytellerReadaloudCacheSyncer
import com.riffle.core.domain.comic.panel.PanelMaskService
import com.riffle.core.domain.localfiles.LocalFilesFolderHealthCheckerInterface
import com.riffle.core.models.SourceType
import com.riffle.feature.library.BookImportManager
import com.riffle.feature.library.CoverImageCopier
import com.riffle.feature.library.EpubTocExtractor
import com.riffle.feature.library.PdfPageCountExtractor
import com.riffle.feature.library.ReadaloudOfflineDownloader
import com.riffle.feature.player.ReadaloudHandoff
import com.riffle.feature.reader.ReaderSyncFactoryInterface
import com.riffle.shared.audiobook.IosAudioPlayerBridge
import com.riffle.shared.audiobook.IosAudioPlayerBridgeFactory
import com.riffle.shared.reader.IosEpubNavigatorBridge
import com.riffle.shared.reader.IosEpubNavigatorBridgeFactory
import com.riffle.shared.reader.IosPdfNavigatorBridge
import com.riffle.shared.reader.IosPdfNavigatorBridgeFactory
import com.riffle.shared.reader.IosPublicationInspector
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.mp.KoinPlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * iOS counterpart to app's `KoinModuleVerificationTest` (issue #1065).
 *
 * Issue #1065 replaced 36 no-op bindings with real implementations that pull in real dependencies,
 * and a missing one does not fail the build — it throws `InstanceCreationException` the first time
 * the app resolves it, which on iOS means a crash after launch. #840 shipped exactly that class of
 * bug. This resolves every binding the issue touched, so a broken graph fails here instead.
 */
class IosKoinGraphTest {

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    // The bridges are created only when a reader/player actually opens; the graph never calls
    // create(), so these stand-ins are enough to start Koin.
    private object StubEpubBridgeFactory : IosEpubNavigatorBridgeFactory {
        override fun create(): IosEpubNavigatorBridge = error("not needed for graph resolution")
    }

    private object StubAudioBridgeFactory : IosAudioPlayerBridgeFactory {
        override fun create(): IosAudioPlayerBridge = error("not needed for graph resolution")
    }

    private object StubPdfBridgeFactory : IosPdfNavigatorBridgeFactory {
        override fun create(): IosPdfNavigatorBridge = error("not needed for graph resolution")
    }

    private object StubPublicationInspector : IosPublicationInspector {
        override fun inspectEpub(filePath: String, onResult: (resultJson: String?) -> Unit) = onResult(null)

        override fun locateProgression(
            filePath: String,
            totalProgression: Double,
            onResult: (locatorJson: String?) -> Unit,
        ) = onResult(null)
    }

    @Test
    fun `every binding issue 1065 implemented resolves from the production graph`() {
        startKoin(
            navigatorBridgeFactory = StubEpubBridgeFactory,
            audioPlayerBridgeFactory = StubAudioBridgeFactory,
            pdfNavigatorBridgeFactory = StubPdfBridgeFactory,
            publicationInspector = StubPublicationInspector,
        )
        val koin = KoinPlatform.getKoin()

        // Each get() throws if the type — or anything it transitively needs — is unbound.
        assertNotNull(koin.get<ApplicationScope>())
        assertNotNull(koin.get<LocalAvailabilityEvents>())

        // EPUB reader infrastructure
        assertNotNull(koin.get<EbookCfiTranslatorFactory>())
        assertNotNull(koin.get<EpubTocExtractor>())

        // Offline audiobooks
        assertNotNull(koin.get<AudiobookDownloadRepository>())
        assertNotNull(koin.get<AudiobookCacheRepository>())
        assertNotNull(koin.get<AudiobookChapterCacheRepository>())

        // Readaloud
        assertNotNull(koin.get<StorytellerReadaloudCacheSyncer>())
        assertNotNull(koin.get<ReadaloudLinkReconciler>())
        assertNotNull(koin.get<ReadaloudReviewRepository>())
        assertNotNull(koin.get<ReadaloudReviewMutator>())
        assertNotNull(koin.get<ReadaloudAudioRepository>())
        assertNotNull(koin.get<ReadaloudBundleReader>())
        assertNotNull(koin.get<BundleAudiobookSource>())
        assertNotNull(koin.get<ReadaloudSidecarPrefetcher>())
        assertNotNull(koin.get<ReadaloudSidecarDownloads>())
        assertNotNull(koin.get<ReadaloudOfflineDownloader>())
        assertNotNull(koin.get<ReadaloudHandoff>())
        assertNotNull(koin.get<CrossEpubIndexStore>())
        assertNotNull(koin.get<CrossEpubIndexBuildTrigger>())
        assertNotNull(koin.get<ReaderSyncFactoryInterface>())

        // Comics, PDF, local files
        assertNotNull(koin.get<PanelMaskService>())
        assertNotNull(koin.get<PdfRepository>())
        assertNotNull(koin.get<PdfPageCountExtractor>())
        assertNotNull(koin.get<CoverImageCopier>())
        assertNotNull(koin.get<LocalFilesFolderHealthCheckerInterface>())
        assertNotNull(koin.get<BookImportManager>())

        // Settings surfaces
        assertNotNull(koin.get<CrashReportRepository>())
        assertNotNull(koin.get<AppUpdateRepository>())
        assertNotNull(koin.get<AppUpdatePreferencesStore>())
    }

    /**
     * #1071 §P0.1 — the production graph must register an Audiobookshelf `CatalogFactory`.
     *
     * `ReadingSessionRepositoryImpl.runSyncCycle` and `AudiobookRepositoryImpl.saveProgress` both
     * resolve their peer through `CatalogRegistry` and return early when it yields null. With
     * Komga as the only entry in `catalogFactoriesBySourceType`, reading or listening on iPhone
     * never moved the book on the user's ABS server, in either medium — while resume *from* the
     * server kept working, so nothing looked broken. Resolving the registry is not enough to
     * catch that (it resolves fine while being empty for ABS), so this asserts the map entry.
     */
    @Test
    fun `the production graph registers an Audiobookshelf catalog factory so progress can be pushed`() {
        startKoin(
            navigatorBridgeFactory = StubEpubBridgeFactory,
            audioPlayerBridgeFactory = StubAudioBridgeFactory,
            pdfNavigatorBridgeFactory = StubPdfBridgeFactory,
            publicationInspector = StubPublicationInspector,
        )
        val koin = KoinPlatform.getKoin()

        assertNotNull(koin.get<CatalogRegistry>())
        val factories = koin.get<Map<SourceType, CatalogFactory>>(named("catalogFactoriesBySourceType"))
        val abs = factories[SourceType.ABS]
        assertNotNull(abs, "no ABS CatalogFactory registered — every ABS progress push silently no-ops")
        assertEquals(SourceType.ABS, abs.sourceType)
        assertTrue(abs is AbsCommonCatalogFactory)
        // Komga must keep working alongside it.
        assertNotNull(factories[SourceType.KOMGA])
    }
}
