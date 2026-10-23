import Foundation
import WebKit
import Riffle
import ReadiumShared
import ReadiumNavigator

// MARK: - IosContinuousEpubSchemeHandler

/// WKURLSchemeHandler serving EPUB resources for the iOS Continuous multi-WebView reader.
///
/// URL space (all under the `riffle-epub` scheme):
///   riffle-epub://pub/<href>          → publication resource (HTML chapters, images, fonts, CSS)
///   riffle-epub://readium-css/<name>  → ReadiumCSS static files from the Readium Navigator bundle
///   riffle-epub://app-fonts/<name>    → app-bundled reader fonts (Literata, Merriweather, OpenDyslexic)
///
/// HTML resources under `pub/` are passed through `htmlTransformer` before serving so the CSS
/// injection and user-preference variables are applied at the byte level — identical to Android's
/// `ContinuousStyleInjector.injectInto` path via `ChapterWebView.shouldInterceptRequest`.
final class IosContinuousEpubSchemeHandler: NSObject, WKURLSchemeHandler {

    static let scheme = "riffle-epub"

    var publication: Publication?

    /// Applied to every HTML/XHTML resource before it is served.
    /// Receives (href, rawHtml) and returns the transformed HTML.
    var htmlTransformer: ((String, String) -> String)?

    // Task tracking: ObjectIdentifier(task) → still-active flag.
    // Both sides run on the main thread (WebKit calls start/stop on main), so no lock needed.
    private var activeTasks: [ObjectIdentifier: Bool] = [:]

    // MARK: - WKURLSchemeHandler

    func webView(_ webView: WKWebView, start urlSchemeTask: WKURLSchemeTask) {
        let taskId = ObjectIdentifier(urlSchemeTask)
        activeTasks[taskId] = true

        guard let url = urlSchemeTask.request.url else {
            urlSchemeTask.didFailWithError(makeError("No URL in request"))
            activeTasks.removeValue(forKey: taskId)
            return
        }

        let host = url.host ?? ""
        let path = (url.path.removingPercentEncoding ?? url.path)

        Task { @MainActor [weak self] in
            guard let self, self.activeTasks[taskId] == true else { return }
            do {
                let (data, mimeType): (Data, String)
                switch host {
                case "pub":
                    (data, mimeType) = try await self.servePublicationResource(path: path)
                case "readium-css":
                    (data, mimeType) = try self.serveReadiumCss(name: self.leafName(path))
                case "app-fonts":
                    (data, mimeType) = try self.serveAppFont(name: self.leafName(path))
                default:
                    throw self.makeError("Unknown host: \(host)")
                }
                guard self.activeTasks[taskId] == true else { return }
                let response = URLResponse(
                    url: url,
                    mimeType: mimeType,
                    expectedContentLength: data.count,
                    textEncodingName: "utf-8"
                )
                urlSchemeTask.didReceive(response)
                urlSchemeTask.didReceive(data)
                urlSchemeTask.didFinish()
            } catch {
                guard self.activeTasks[taskId] == true else { return }
                urlSchemeTask.didFailWithError(error)
            }
            self.activeTasks.removeValue(forKey: taskId)
        }
    }

    func webView(_ webView: WKWebView, stop urlSchemeTask: WKURLSchemeTask) {
        activeTasks[ObjectIdentifier(urlSchemeTask)] = false
    }

    // MARK: - Resource serving

    private func servePublicationResource(path: String) async throws -> (Data, String) {
        guard let pub = publication else {
            throw makeError("No publication loaded")
        }
        let href = path.hasPrefix("/") ? String(path.dropFirst()) : path
        let allLinks = pub.readingOrder + pub.resources
        guard let link = allLinks.first(where: { normalizeHref($0.href) == normalizeHref(href) }) else {
            throw makeError("Resource not found: \(href)", code: 404)
        }
        guard let resource = pub.get(link) else {
            throw makeError("Cannot open resource: \(href)")
        }
        guard case .success(var data) = await resource.read() else {
            throw makeError("Cannot read resource: \(href)")
        }
        let mime = link.mediaType?.string ?? mimeType(for: href)
        if mime.contains("html") || mime.contains("xhtml"),
           let transformer = htmlTransformer,
           let html = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) {
            data = transformer(href, html).data(using: .utf8) ?? data
        }
        return (data, mime)
    }

    private func serveReadiumCss(name: String) throws -> (Data, String) {
        let bundle = Bundle(for: EPUBNavigatorViewController.self)
        guard let url = bundle.url(forResource: name, withExtension: nil,
                                   subdirectory: "Assets/Static/readium-css"),
              let data = try? Data(contentsOf: url) else {
            throw makeError("ReadiumCSS not found: \(name)", code: 404)
        }
        return (data, "text/css")
    }

    private func serveAppFont(name: String) throws -> (Data, String) {
        guard let url = Bundle.main.url(forResource: name, withExtension: nil),
              let data = try? Data(contentsOf: url) else {
            throw makeError("App font not found: \(name)", code: 404)
        }
        let ext = (name as NSString).pathExtension.lowercased()
        return (data, ext == "otf" ? "font/otf" : "font/truetype")
    }

    // MARK: - Helpers

    private func normalizeHref(_ href: String) -> String {
        href.hasPrefix("/") ? String(href.dropFirst()) : href
    }

    private func leafName(_ path: String) -> String {
        let stripped = path.hasPrefix("/") ? String(path.dropFirst()) : path
        return (stripped as NSString).lastPathComponent
    }

    private func mimeType(for href: String) -> String {
        switch (href as NSString).pathExtension.lowercased() {
        case "xhtml": return "application/xhtml+xml"
        case "html", "htm": return "text/html"
        case "css": return "text/css"
        case "js": return "text/javascript"
        case "png": return "image/png"
        case "jpg", "jpeg": return "image/jpeg"
        case "gif": return "image/gif"
        case "svg": return "image/svg+xml"
        case "ttf": return "font/truetype"
        case "otf": return "font/otf"
        case "woff": return "font/woff"
        case "woff2": return "font/woff2"
        default: return "application/octet-stream"
        }
    }

    private func makeError(_ msg: String, code: Int = -1) -> Error {
        NSError(domain: "IosContinuousEpubSchemeHandler", code: code,
                userInfo: [NSLocalizedDescriptionKey: msg])
    }
}

// MARK: - IosContinuousCssInjector

/// Injects ReadiumCSS links and user-preference CSS variables into raw EPUB HTML.
///
/// Mirrors `ContinuousStyleInjector.injectInto` from Android so that iOS Continuous mode renders
/// text with the same theme, font, line-height and margin settings as Android Continuous and the
/// two scroll modes. The CSS framework files themselves are served by [IosContinuousEpubSchemeHandler]
/// from the `Readium_ReadiumNavigator.bundle` that is already embedded in the app.
enum IosContinuousCssInjector {

    private static let cssBase = "\(IosContinuousEpubSchemeHandler.scheme)://readium-css"
    private static let fontBase = "\(IosContinuousEpubSchemeHandler.scheme)://app-fonts"

    // MARK: - HTML injection

    static func injectInto(html: String, href: String, prefs: IosReaderPreferences) -> String {
        var out = html
        let hasAuthorStyles = html.range(of: "<style", options: .caseInsensitive) != nil
            || html.range(of: "<link ", options: .caseInsensitive) != nil

        // 1. Before-CSS (+ default-CSS when no author styles) after <head>.
        let beforeBlock = """
        \n<link rel="stylesheet" type="text/css" href="\(cssBase)/ReadiumCSS-before.css"/>\n\
        <style>:root[style],:root{overflow:visible!important;}:root[style]>body,:root>body{overflow:visible!important;}\
        :root{--RS__maxMediaHeight:none;}</style>\n\
        \(hasAuthorStyles ? "" : "<link rel=\"stylesheet\" type=\"text/css\" href=\"\(cssBase)/ReadiumCSS-default.css\"/>\n")
        """
        if let headRange = out.range(of: "<head[^>]*>", options: [.regularExpression, .caseInsensitive], locale: nil) {
            let insertAt = headRange.upperBound
            out.insert(contentsOf: beforeBlock, at: insertAt)
        } else {
            out = beforeBlock + out
        }

        // 2. After-CSS + bundled @font-face + height measurement script before </head>.
        let afterBlock = """
        \n<link rel="stylesheet" type="text/css" href="\(cssBase)/ReadiumCSS-after.css"/>\n\
        <style type="text/css">\n\(bundledFontFaces())\n</style>\n\
        \(domReadyScript())
        """
        if let headCloseRange = out.range(of: "</head>", options: .caseInsensitive) {
            out.insert(contentsOf: afterBlock, at: headCloseRange.lowerBound)
        } else {
            out += afterBlock
        }

        // 3. --USER__* style attribute on <html>.
        let styleAttr = buildHtmlStyleAttr(prefs: prefs).replacingOccurrences(of: "\"", with: "&quot;")
        if let htmlTagRange = out.range(of: "<html[^>]*", options: [.regularExpression, .caseInsensitive]) {
            let insertAt = htmlTagRange.upperBound
            out.insert(contentsOf: " style=\"\(styleAttr)\"", at: insertAt)
        }
        return out
    }

    // MARK: - Live preference update JS

    static func buildStyleSetJs(prefs: IosReaderPreferences) -> String {
        let attr = buildHtmlStyleAttr(prefs: prefs)
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
        return "(function(){document.documentElement.setAttribute('style','\(attr)');})();"
    }

    // MARK: - CSS variable builder

    static func buildHtmlStyleAttr(prefs: IosReaderPreferences) -> String {
        var props: [(String, String)] = []

        // Always scrolled in Continuous mode.
        props.append(("--USER__view", "readium-scroll-on"))

        // Page margins.
        props.append(("--USER__pageMargins", String(prefs.pageMargins)))

        // Appearance.
        switch prefs.theme {
        case "dark":  props.append(("--USER__appearance", "readium-night-on"))
        case "sepia": props.append(("--USER__appearance", "readium-sepia-on"))
        default: break
        }

        // DarkDim muted text colour.
        if prefs.textColorArgb != 0 {
            let red = (prefs.textColorArgb >> 16) & 0xFF
            let grn = (prefs.textColorArgb >> 8) & 0xFF
            let blu = prefs.textColorArgb & 0xFF
            props.append(("--USER__textColor", String(format: "#%02X%02X%02X", red, grn, blu)))
        }

        // Font family.
        if !prefs.fontFamilyCss.isEmpty {
            props.append(("--USER__fontOverride", "readium-font-on"))
            props.append(("--USER__fontFamily", "\"\(prefs.fontFamilyCss)\""))
        }

        // Font size: fontSizePercent is a multiplier (1.0 = 100 %, 1.5 = 150 %).
        let pct = prefs.fontSizePercent * 100
        let pctStr = pct.truncatingRemainder(dividingBy: 1) == 0
            ? "\(Int(pct))%" : "\(pct)%"
        props.append(("--USER__fontSize", pctStr))

        // Always advanced settings (publisher styles off).
        props.append(("--USER__advancedSettings", "readium-advanced-on"))

        // Text alignment.
        if prefs.justifyText {
            props.append(("--USER__textAlign", "justify"))
        }

        // Line height.
        if prefs.lineHeightMultiplier > 0 {
            props.append(("--USER__lineHeight", String(prefs.lineHeightMultiplier)))
        }

        return props.map { "\($0.0): \($0.1) !important;" }.joined(separator: " ")
    }

    // MARK: - Private helpers

    private static func bundledFontFaces() -> String {
        "@font-face{font-family:Literata;font-style:normal;font-weight:400;" +
        "src:url('\(fontBase)/Literata-Regular.ttf') format('truetype');}\n" +
        "@font-face{font-family:Merriweather;font-style:normal;font-weight:400;" +
        "src:url('\(fontBase)/Merriweather-Regular.ttf') format('truetype');}\n" +
        "@font-face{font-family:OpenDyslexic;font-style:normal;font-weight:400;" +
        "src:url('\(fontBase)/OpenDyslexic-Regular.otf') format('opentype');}\n"
    }

    private static func domReadyScript() -> String {
        "<script type=\"text/javascript\">//<![CDATA[\n" +
        "(function(){document.addEventListener('DOMContentLoaded',function(){" +
        "if(window.RiffleChapter&&window.RiffleChapter.onDomReady){window.RiffleChapter.onDomReady();}});})();\n" +
        "//]]></script>\n"
    }
}
