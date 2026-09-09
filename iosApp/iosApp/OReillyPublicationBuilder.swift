import Foundation
import ReadiumShared
import ReadiumNavigator
import ReadiumStreamer
import Riffle

/// Assembles a Readium Swift `Publication` for an O'Reilly book using lazy per-chapter fetching.
///
/// Mirror of Android's `OReillyPublicationBuilder`: decodes `shapeJson` (produced by
/// `IosLazyChapterFetcherImpl.serializeShape`), builds an `OReillyLazyContainer`, constructs the
/// `Manifest` and `Publication`.  No EPUB file is required.
enum OReillyPublicationBuilder {

    enum BuildError: Error {
        case invalidShapeJson
    }

    static func build(
        shapeJson: String,
        fetcher: any IosLazyChapterFetcher
    ) throws -> (publication: Publication, container: OReillyLazyContainer) {
        guard let data = shapeJson.data(using: .utf8),
              let shape = try? JSONDecoder().decode(LazyPublicationShapeDto.self, from: data) else {
            throw BuildError.invalidShapeJson
        }

        let container = OReillyLazyContainer(shape: shape, fetcher: fetcher)

        let readingOrder: [Link] = shape.spine.map { item in
            Link(
                href: item.fullPath,
                mediaType: .xhtml,
                title: item.title.isEmpty ? "Chapter \(item.index + 1)" : item.title
            )
        }

        let manifest = Manifest(
            metadata: Metadata(
                conformsTo: [.epub],
                identifier: shape.identifier,
                title: shape.title,
                languages: [shape.language]
            ),
            readingOrder: readingOrder,
            tableOfContents: readingOrder
        )

        // Positions are estimated from the declared byte sizes reported by LazyChapterResource.estimatedLength().
        let publication = try Publication(
            manifest: manifest,
            container: container,
            servicesBuilder: PublicationServicesBuilder(
                positions: EPUBPositionsService.makeFactory(
                    reflowableStrategy: .recommended
                )
            )
        )

        return (publication, container)
    }
}
