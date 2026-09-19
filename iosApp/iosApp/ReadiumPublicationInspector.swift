import Foundation
import Riffle
import ReadiumShared
import ReadiumStreamer

// MARK: - ReadiumPublicationInspector

/// Implements IosPublicationInspector (generated from Kotlin shared's IosPublicationInspector).
/// Opens a Readium Swift Publication headlessly — no navigator, no view controller — just to read
/// its table of contents and position count. One stateless instance serves every call; unlike
/// ReadiumEpubNavigatorBridge there is no per-book session to keep alive.
@objc class ReadiumPublicationInspector: NSObject, IosPublicationInspector {

    func inspectEpub(filePath: String, onResult: @escaping (String?) -> Void) {
        Task {
            let resultJson = await Self.inspect(filePath: filePath)
            await MainActor.run { onResult(resultJson) }
        }
    }

    private static func inspect(filePath: String) async -> String? {
        guard let fileURL = FileURL(path: filePath, isDirectory: false) else { return nil }

        let httpClient = DefaultHTTPClient()
        let assetRetriever = AssetRetriever(httpClient: httpClient)
        guard case .success(let asset) = await assetRetriever.retrieve(url: fileURL) else { return nil }

        let opener = PublicationOpener(parser: CompositePublicationParser([EPUBParser()]))
        guard case .success(let publication) = await opener.open(asset: asset, allowUserInteraction: false) else {
            return nil
        }

        guard case .success(let tocLinks) = await publication.tableOfContents() else { return nil }
        let tocJson = serializeTocLinks(tocLinks)

        let totalPositions: Int?
        switch await publication.positions() {
        case .success(let positions):
            totalPositions = positions.count
        case .failure:
            totalPositions = nil
        }

        let escapedTocJson = tocJson.jsonEscaped
        let positionsField = totalPositions.map { "\($0)" } ?? "null"
        return #"{"tocJson":"\#(escapedTocJson)","totalPositions":\#(positionsField)}"#
    }

    private static func serializeTocLinks(_ links: [Link]) -> String {
        let items = links.map { serializeTocLink($0) }.joined(separator: ",")
        return "[\(items)]"
    }

    private static func serializeTocLink(_ link: Link) -> String {
        let title = (link.title ?? "").jsonEscaped
        let href = link.href.jsonEscaped
        let children = link.children.isEmpty ? "[]" : serializeTocLinks(link.children)
        return #"{"title":"\#(title)","href":"\#(href)","children":\#(children)}"#
    }
}

// Mirror of the canonical Kotlin implementation in JsonStringUtils.kt (commonMain), and of the
// file-private copy in ReadiumEpubNavigatorBridge.swift. Any change to the escaping logic must be
// applied to all three.
private extension String {
    var jsonEscaped: String {
        replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
            .replacingOccurrences(of: "\r", with: "\\r")
    }
}
