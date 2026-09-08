import Foundation
import ReadiumShared

// MARK: - Shape DTOs (decoded from IosLazyChapterFetcherImpl.serializeShape JSON)

struct LazyPublicationShapeDto: Codable {
    let bookId: String
    let identifier: String
    let title: String
    let language: String
    let spine: [LazySpineItemDto]
    let absoluteFilesPrefix: String
    let pathFilesPrefix: String
    let cssFullPaths: [String]
}

struct LazySpineItemDto: Codable {
    let index: Int
    let fullPath: String
    let title: String
    let declaredByteSize: Int64
}

// MARK: - Container

/// Readium Swift `Container` for O'Reilly lazy publications.  Each chapter resource is served on
/// demand via `IosLazyChapterFetcher` (backed by `IosLazyChapterFetcherImpl` on the Kotlin side),
/// which fetches raw HTML, assembles XHTML via the shared `OReillyEpub` helpers, and caches to
/// disk.  Subsequent reads of the same chapter are served from cache — no network round-trip.
final class OReillyLazyContainer: Container {

    let shape: LazyPublicationShapeDto
    private let fetcher: any IosLazyChapterFetcher

    var sourceURL: (any AbsoluteURL)? { nil }

    var entries: Set<AnyURL> {
        Set(shape.spine.compactMap { AnyURL(string: $0.fullPath) })
    }

    init(shape: LazyPublicationShapeDto, fetcher: any IosLazyChapterFetcher) {
        self.shape = shape
        self.fetcher = fetcher
    }

    subscript(_ url: any URLConvertible) -> (any Resource)? {
        let urlStr = url.string
        if let spineItem = shape.spine.first(where: { urlStr.hasSuffix($0.fullPath) || $0.fullPath == urlStr }) {
            return LazyChapterResource(spineItem: spineItem, fetcher: fetcher)
        }
        let relativePath = Self.extractRelativePath(urlStr)
        return LazyAssetResource(fullPath: relativePath, fetcher: fetcher)
    }

    func close() async {}

    /// Mirror of `OReillyLazyContainer.extractRelativePath` on Android: strips the
    /// `https://readium_package/` origin Readium prepends to sub-resource URLs.
    static func extractRelativePath(_ urlString: String) -> String {
        var s = urlString
        let prefix = "https://readium_package/"
        if s.hasPrefix(prefix) {
            s = String(s.dropFirst(prefix.count))
        }
        if s.hasPrefix("/") {
            s = String(s.dropFirst())
        }
        return s
    }
}

// MARK: - Chapter resource

/// `Resource` for a single spine chapter: delegates to `IosLazyChapterFetcher.fetchChapterXhtmlPath`.
private final class LazyChapterResource: Resource {

    var sourceURL: (any AbsoluteURL)? { nil }

    private let spineItem: LazySpineItemDto
    private let fetcher: any IosLazyChapterFetcher

    init(spineItem: LazySpineItemDto, fetcher: any IosLazyChapterFetcher) {
        self.spineItem = spineItem
        self.fetcher = fetcher
    }

    func properties() async -> ReadResult<ResourceProperties> {
        .success(ResourceProperties())
    }

    func estimatedLength() async -> ReadResult<UInt64?> {
        .success(UInt64(bitPattern: spineItem.declaredByteSize))
    }

    func read(range: Range<UInt64>?) async -> ReadResult<Data> {
        return await withCheckedContinuation { continuation in
            fetcher.fetchChapterXhtmlPath(
                spineItem.fullPath,
                expectedByteSize: spineItem.declaredByteSize
            ) { filePath in
                guard let filePath = filePath,
                      let data = try? Data(contentsOf: URL(fileURLWithPath: filePath)) else {
                    continuation.resume(returning: .failure(.decoding(nil)))
                    return
                }
                if let range = range {
                    let start = Int(min(range.lowerBound, UInt64(data.count)))
                    let end = Int(min(range.upperBound, UInt64(data.count)))
                    continuation.resume(returning: .success(data.subdata(in: start..<end)))
                } else {
                    continuation.resume(returning: .success(data))
                }
            }
        }
    }

    func close() async {}
}

// MARK: - Asset resource

/// `Resource` for CSS, images, and other binary assets: delegates to
/// `IosLazyChapterFetcher.fetchAssetPath`.
private final class LazyAssetResource: Resource {

    var sourceURL: (any AbsoluteURL)? { nil }

    private let fullPath: String
    private let fetcher: any IosLazyChapterFetcher

    init(fullPath: String, fetcher: any IosLazyChapterFetcher) {
        self.fullPath = fullPath
        self.fetcher = fetcher
    }

    func properties() async -> ReadResult<ResourceProperties> {
        .success(ResourceProperties())
    }

    func estimatedLength() async -> ReadResult<UInt64?> {
        .success(nil)
    }

    func read(range: Range<UInt64>?) async -> ReadResult<Data> {
        return await withCheckedContinuation { continuation in
            fetcher.fetchAssetPath(fullPath) { filePath in
                guard let filePath = filePath,
                      let data = try? Data(contentsOf: URL(fileURLWithPath: filePath)) else {
                    continuation.resume(returning: .failure(.decoding(nil)))
                    return
                }
                if let range = range {
                    let start = Int(min(range.lowerBound, UInt64(data.count)))
                    let end = Int(min(range.upperBound, UInt64(data.count)))
                    continuation.resume(returning: .success(data.subdata(in: start..<end)))
                } else {
                    continuation.resume(returning: .success(data))
                }
            }
        }
    }

    func close() async {}
}
