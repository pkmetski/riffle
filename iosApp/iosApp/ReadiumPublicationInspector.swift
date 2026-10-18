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

    /// Read one resource from an EPUB by its href and return its bytes Base64-encoded.
    /// Used by IosElidedEpubAssembler to embed figure data URIs in the synthetic EPUB HTML.
    func readEpubResourceBase64(filePath: String, href: String, onResult: @escaping (String?) -> Void) {
        Task {
            let base64 = await Self.readResourceBase64(filePath: filePath, href: href)
            await MainActor.run { onResult(base64) }
        }
    }

    /// Extracts all @font-face rules from the EPUB's CSS resources and rewrites each font `src`
    /// to an inline data: URI, returning the combined CSS string. Used to populate publisherFontFaceCss
    /// for the elided Annotations View so the synthetic EPUB renders the book's publisher typeface.
    func extractPublisherFontFaceCss(filePath: String, onResult: @escaping (String) -> Void) {
        Task {
            let css = await Self.extractFontFaceCss(filePath: filePath)
            await MainActor.run { onResult(css) }
        }
    }

    /// Fallback inbound-sync path: resolve a whole-book progression to a Locator via Readium's
    /// `Publication.locate(progression:)`. iOS previously had no equivalent of Android's
    /// `locateProgression`, so a server position that arrived as a bare `ebookProgress` float with
    /// no usable CFI could not be turned into a navigable position at all.
    func locateProgression(filePath: String, totalProgression: Double, onResult: @escaping (String?) -> Void) {
        Task {
            let locatorJson = await Self.locate(filePath: filePath, totalProgression: totalProgression)
            await MainActor.run { onResult(locatorJson) }
        }
    }

    /// Extracts @font-face rules from all CSS resources in the publication, rewriting each font
    /// url() to an inline data: URI. Mirrors Android's PublisherFontFaceExtractor logic.
    private static func extractFontFaceCss(filePath: String) async -> String {
        guard let publication = await openPublication(filePath: filePath) else { return "" }

        let cssLinks = publication.resources.filter { link in
            link.mediaType?.string == "text/css" || link.href.lowercased().hasSuffix(".css")
        }

        var out = ""
        for link in cssLinks {
            guard let resource = publication.get(link) else { continue }
            guard case .success(let data) = await resource.read() else { continue }
            guard let css = String(data: data, encoding: .utf8) else { continue }
            let cssDir: String = {
                let href = link.href
                let lastSlash = href.lastIndex(of: "/")
                guard let idx = lastSlash else { return "" }
                return String(href[href.startIndex...idx])
            }()
            out += await rewriteFontFaceRules(css: css, cssDir: cssDir, publication: publication)
        }
        return out
    }

    private static func rewriteFontFaceRules(css: String, cssDir: String, publication: Publication) async -> String {
        let stripped = stripCssComments(css)
        var result = ""
        var searchFrom = stripped.startIndex
        let marker = "@font-face"
        while let markerRange = stripped.range(of: marker, options: .caseInsensitive, range: searchFrom..<stripped.endIndex) {
            guard let openBrace = stripped[markerRange.upperBound...].firstIndex(of: "{") else { break }
            let between = stripped[markerRange.upperBound..<openBrace]
            guard between.allSatisfy({ $0.isWhitespace }) else {
                searchFrom = markerRange.upperBound
                continue
            }
            var depth = 1
            var ruleEnd = stripped.index(after: openBrace)
            while ruleEnd < stripped.endIndex && depth > 0 {
                if stripped[ruleEnd] == "{" { depth += 1 }
                else if stripped[ruleEnd] == "}" { depth -= 1 }
                ruleEnd = stripped.index(after: ruleEnd)
            }
            guard depth == 0 else { break }
            let rule = String(stripped[markerRange.lowerBound..<ruleEnd])
            if let rewritten = await rewriteUrls(in: rule, cssDir: cssDir, publication: publication) {
                result += rewritten + "\n"
            }
            searchFrom = ruleEnd
        }
        return result
    }

    private static func rewriteUrls(in rule: String, cssDir: String, publication: Publication) async -> String? {
        let urlPattern = try? NSRegularExpression(pattern: #"url\(\s*([^)]+?)\s*\)"#, options: .caseInsensitive)
        guard let urlPattern else { return nil }
        var anyResolved = false
        let fullRange = NSRange(rule.startIndex..., in: rule)
        let matches = urlPattern.matches(in: rule, range: fullRange)
        var parts: [String] = []
        var lastEnd = rule.startIndex
        for match in matches {
            guard let matchRange = Range(match.range, in: rule),
                  let groupRange = Range(match.range(at: 1), in: rule) else { continue }
            parts.append(String(rule[lastEnd..<matchRange.lowerBound]))
            let rawPath = String(rule[groupRange]).trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
            if rawPath.lowercased().hasPrefix("data:") {
                anyResolved = true
                parts.append(String(rule[matchRange]))
            } else {
                let resolvedPath = resolveFontPath(rawPath, cssDir: cssDir)
                if let fontData = await readFont(path: resolvedPath, cssDir: cssDir, rawPath: rawPath, publication: publication) {
                    anyResolved = true
                    let mime = mimeForFontPath(rawPath)
                    let b64 = fontData.base64EncodedString()
                    parts.append("url(\"data:\(mime);base64,\(b64)\")")
                } else {
                    parts.append(String(rule[matchRange]))
                }
            }
            lastEnd = matchRange.upperBound
        }
        parts.append(String(rule[lastEnd...]))
        return anyResolved ? parts.joined() : nil
    }

    private static func resolveFontPath(_ rel: String, cssDir: String) -> String {
        if rel.hasPrefix("/") { return String(rel.dropFirst()) }
        let combined = cssDir + rel
        let parts = combined.split(separator: "/", omittingEmptySubsequences: true).map(String.init)
        var stack: [String] = []
        for part in parts {
            if part == "." { continue }
            else if part == ".." { if !stack.isEmpty { stack.removeLast() } }
            else { stack.append(part) }
        }
        return stack.joined(separator: "/")
    }

    private static func readFont(path: String, cssDir: String, rawPath: String, publication: Publication) async -> Data? {
        var candidates = [path]
        if rawPath != path { candidates.append(rawPath.hasPrefix("/") ? String(rawPath.dropFirst()) : rawPath) }
        if rawPath.hasPrefix("/") {
            let bare = String(rawPath.dropFirst())
            candidates += ["OEBPS/\(bare)", "EPUB/\(bare)", "content/\(bare)"]
        }
        for candidate in candidates {
            let link = publication.resources.first { $0.href == candidate }
                ?? publication.readingOrder.first { $0.href == candidate }
            guard let link, let resource = publication.get(link) else { continue }
            if case .success(let data) = await resource.read() { return data }
        }
        return nil
    }

    private static func mimeForFontPath(_ path: String) -> String {
        let lower = path.lowercased()
        if lower.hasSuffix(".woff2") { return "font/woff2" }
        if lower.hasSuffix(".woff") { return "font/woff" }
        if lower.hasSuffix(".otf") { return "font/otf" }
        if lower.hasSuffix(".ttf") { return "font/ttf" }
        return "application/octet-stream"
    }

    private static func stripCssComments(_ css: String) -> String {
        var result = ""
        var pos = css.startIndex
        while pos < css.endIndex {
            if css[pos] == "/" && css.index(after: pos) < css.endIndex && css[css.index(after: pos)] == "*" {
                if let end = css.range(of: "*/", range: css.index(pos, offsetBy: 2)..<css.endIndex) {
                    pos = end.upperBound
                } else {
                    break
                }
            } else {
                result.append(css[pos])
                pos = css.index(after: pos)
            }
        }
        return result
    }

    private static func readResourceBase64(filePath: String, href: String) async -> String? {
        guard let publication = await openPublication(filePath: filePath) else { return nil }
        let link = publication.readingOrder.first { $0.href == href }
            ?? publication.resources.first { $0.href == href }
        guard let link else { return nil }
        guard let resource = publication.get(link) else { return nil }
        guard case .success(let data) = await resource.read() else { return nil }
        return data.base64EncodedString()
    }

    private static func locate(filePath: String, totalProgression: Double) async -> String? {
        guard (0.0...1.0).contains(totalProgression) else { return nil }
        guard let publication = await openPublication(filePath: filePath) else { return nil }
        guard let locator = await publication.locate(progression: totalProgression) else { return nil }
        return try? locator.jsonString()
    }

    private static func openPublication(filePath: String) async -> Publication? {
        guard let fileURL = FileURL(path: filePath, isDirectory: false) else { return nil }

        let httpClient = DefaultHTTPClient()
        let assetRetriever = AssetRetriever(httpClient: httpClient)
        guard case .success(let asset) = await assetRetriever.retrieve(url: fileURL) else { return nil }

        let opener = PublicationOpener(parser: CompositePublicationParser([EPUBParser()]))
        guard case .success(let publication) = await opener.open(asset: asset, allowUserInteraction: false) else {
            return nil
        }
        return publication
    }

    private static func inspect(filePath: String) async -> String? {
        guard let publication = await openPublication(filePath: filePath) else { return nil }

        guard case .success(let tocLinks) = await publication.tableOfContents() else { return nil }
        let tocJson = serializeTocLinks(tocLinks)

        let totalPositions: Int?
        switch await publication.positions() {
        case .success(let positions):
            totalPositions = positions.count
        case .failure:
            totalPositions = nil
        }

        // The spine, in reading order. Kotlin's TOC extractor ignores it; the progression-fallback
        // tests need it to turn a resolved Locator href into a spine index.
        let readingOrder = publication.readingOrder
            .map { #""\#($0.href.jsonEscaped)""# }
            .joined(separator: ",")

        let escapedTocJson = tocJson.jsonEscaped
        let positionsField = totalPositions.map { "\($0)" } ?? "null"
        return #"{"tocJson":"\#(escapedTocJson)","totalPositions":\#(positionsField),"readingOrder":[\#(readingOrder)]}"#
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
