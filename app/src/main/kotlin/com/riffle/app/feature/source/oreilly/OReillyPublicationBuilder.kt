package com.riffle.app.feature.source.oreilly

import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Publication
import org.readium.r2.streamer.parser.epub.EpubPositionsService
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * Assembles a Readium [Publication] for an O'Reilly book using lazy per-chapter fetching.
 *
 * The returned publication has one [Link] per spine item. Each link's URL matches the
 * [LazySpineItem.fullPath] as resolved by [urlFactory]. Readium fetches resources through
 * [OReillyLazyContainer], which downloads and caches each chapter on first access.
 *
 * No search service is registered — O'Reilly content is HTML scraped and would need a separate
 * extraction pass. [EpubPositionsService] with OriginalLength(1024) gives within-chapter positions
 * based on [LazySpineItem.declaredByteSize].
 */
object OReillyPublicationBuilder {

    @OptIn(ExperimentalReadiumApi::class)
    fun build(
        container: OReillyLazyContainer,
        urlFactory: (String) -> Url? = { Url(it) },
    ): Publication {
        val pub = container.pub
        val readingOrder = pub.spine.mapNotNull { item ->
            val url = urlFactory(item.fullPath) ?: return@mapNotNull null
            Link(
                href = url,
                mediaType = MediaType.XHTML,
                title = item.title.ifBlank { "Chapter ${item.index + 1}" },
            )
        }

        val manifest = Manifest(
            metadata = Metadata(
                conformsTo = setOf(Publication.Profile.EPUB),
                localizedTitle = LocalizedString(pub.title),
                languages = listOf(pub.language),
                identifier = pub.identifier,
            ),
            readingOrder = readingOrder,
            tableOfContents = readingOrder,
        )

        return Publication(
            manifest = manifest,
            container = container,
            servicesBuilder = Publication.ServicesBuilder(
                // OriginalLength uses Resource.length() (our LazyChapterResource returns
                // declaredByteSize) to estimate positions within each chapter, giving
                // within-chapter progress in the chapter map.
                positions = EpubPositionsService.createFactory(
                    reflowableStrategy = EpubPositionsService.ReflowableStrategy.OriginalLength(
                        pageLength = 1024,
                    ),
                ),
            ),
        )
    }
}
