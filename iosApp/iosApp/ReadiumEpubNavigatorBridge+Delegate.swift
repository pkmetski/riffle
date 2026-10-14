import UIKit
import WebKit
import Riffle
import ReadiumShared
import ReadiumNavigator

// MARK: - EPUBNavigatorDelegate

extension ReadiumEpubNavigatorBridge: EPUBNavigatorDelegate {
    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        guard let json = try? locator.jsonString() else { return }
        cachedLocatorJson = json
        locatorCallback?(json)
        emitSelectionIfCleared()
        let href = locator.href.string
        if href != lastLoadedHref {
            lastLoadedHref = href
            pageLoadCallback?()
        }
        // Re-apply on every navigation: Readium may have instantiated new WKWebViews for
        // adjacent preloaded spreads since the last call.
        if let navView = epubNavigator?.view { disableScrollsToTop(in: navView) }
    }

    func navigator(_ navigator: VisualNavigator, didTapAt point: CGPoint) {
        emitSelectionIfCleared()
        let size = navigator.view.bounds.size
        tapCallback?(TapCoords(x: Double(point.x), y: Double(point.y), viewWidth: Double(size.width), viewHeight: Double(size.height)))
    }

    /// Called by Readium for each spread's WKWebView before its page content loads.
    ///
    /// Injects a thin shim that maps Android's `window.RiffleFigureBridge.onFigureTap(payload)`
    /// call convention to WKWebView's `window.webkit.messageHandlers.*` API, then registers
    /// `self` as the `WKScriptMessageHandler` so the message arrives in `userContentController`.
    ///
    /// The message handler name must match `FigureTapScript.PAGED_BRIDGE_NAME` ("RiffleFigureBridge").
    func navigator(
        _ navigator: EPUBNavigatorViewController,
        setupUserScripts userContentController: WKUserContentController
    ) {
        let shim = """
            window.RiffleFigureBridge = {
                onFigureTap: function(p) {
                    window.webkit.messageHandlers.RiffleFigureBridge.postMessage(p);
                }
            };
            """
        // Remove before add — idempotent guard against duplicate registration if Readium calls
        // setupUserScripts more than once for the same WKUserContentController.
        userContentController.removeScriptMessageHandler(forName: "RiffleFigureBridge")
        userContentController.add(self, name: "RiffleFigureBridge")
        userContentController.addUserScript(WKUserScript(
            source: shim,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false
        ))
        figureHandlerControllers.add(userContentController)

        // Typography overrides: inject after every page load, regardless of chapter href or mode.
        //
        // The Kotlin-side LaunchedEffect injection (injectTypographyOverrideScript via
        // pageLoadEvents) has a known gap: when switching between paginated and continuous mode,
        // Readium reloads all spreads but locationDidChange fires with the *same* chapter href,
        // so the bridge's href-change guard suppresses pageLoadCallback — the freshly reloaded
        // spread never gets the <style> tag. Preloaded chapters share the same gap: they are
        // loaded in the background before the user reaches them, without going through
        // locationDidChange.
        //
        // Registering a WKUserScript closes both gaps: it fires on every WKWebView page load
        // (atDocumentEnd, after Readium has baked its CSS variables into the <html style="…">
        // attribute via cssPropertiesInjection), so our gate [style*="--USER__textAlign"] always
        // sees the correct `:root` style. The script is idempotent — the stable element id
        // prevents duplicate <style> tags on re-injection.
        installTypographyOverrideScript(into: userContentController)
    }

    /// Extracted for testability — `EPUBNavigatorViewController` cannot be constructed in unit
    /// tests (requires a live `Publication`), so the test verifies the configuration directly.
    internal func makeNavigatorConfiguration() -> EPUBNavigatorViewController.Configuration {
        EPUBNavigatorViewController.Configuration(
            preferences: pendingPreferences,
            disablePageTurnsWhileScrolling: true,
            decorationTemplates: RiffleDecorationTemplates.all()
        )
    }

    /// Registers the typography override as a WKUserScript on `controller`.
    ///
    /// Extracted for testability — `EPUBNavigatorViewController` cannot be constructed in unit
    /// tests (requires a live `Publication`), so the test calls this helper directly.
    internal func installTypographyOverrideScript(into controller: WKUserContentController) {
        controller.addUserScript(WKUserScript(
            source: TypographyOverrideKt.TYPOGRAPHY_OVERRIDE_INSTALL_SCRIPT,
            injectionTime: .atDocumentEnd,
            forMainFrameOnly: true
        ))
    }

    /// Report a *cleared* selection.
    ///
    /// `shouldShowMenuForSelection` only fires for a new, non-nil selection —
    /// `EditingActionsController` sets `isEnabled = false` and notifies nobody when the WKWebView
    /// reports the selection gone. Without this the annotate sheet would stay on screen over a
    /// page the user has already deselected, or already turned away from. These two delegate
    /// callbacks are exactly the moments a selection can disappear: a tap elsewhere, and a page
    /// turn.
    private func emitSelectionIfCleared() {
        guard epubNavigator?.currentSelection == nil else { return }
        selectionCallback?(nil)
    }

    /// Tapping an external link in a book did nothing until #1071 §17 — this delegate method was
    /// an empty stub. Android hands the URL to `Intent.ACTION_VIEW`
    /// (`EpubReaderScreen.kt:1625-1632`); the iOS equivalent is `UIApplication.open`.
    ///
    /// `riffle://` URLs are accent-bar tap targets injected by `ElidedChapterHtmlBuilder` in the
    /// Highlights-mode synthetic EPUB. They must NOT reach the system URL opener (Safari would
    /// refuse `riffle://` and do nothing). Forward them to the Kotlin side via [riffleUrlCallback]
    /// and return early so Safari is never involved.
    func navigator(_ navigator: Navigator, presentExternalURL url: URL) {
        if url.scheme == "riffle", let cb = riffleUrlCallback {
            cb(url.absoluteString)
            return
        }
        urlOpener(url)
    }

    /// Navigator errors (e.g. `.copyForbidden`) were swallowed by an empty stub. Forward them to
    /// the Kotlin side so they reach the RIFFLE_READER log channel instead of vanishing.
    func navigator(_ navigator: Navigator, presentError error: NavigatorError) {
        errorCallback?(String(describing: error))
    }

    /// Readium-Swift 3.x calls this when the user taps a `epub:type="noteref"` anchor. When a
    /// [footnoteCallback] is registered we strip the note HTML to plain text, forward it, and
    /// return `false` so the navigator does NOT jump to the note location — the shared
    /// `FootnotePopup` composable shows it inline instead.
    func navigator(_ navigator: Navigator, shouldNavigateToNoteAt link: Link, content: String, referrer: String?) -> Bool {
        guard let cb = footnoteCallback else { return true }
        let plain = content
            .replacingOccurrences(of: "<[^>]+>", with: " ", options: .regularExpression)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        cb(plain.isEmpty ? content : plain)
        return false
    }

    /// Internal-link seam — captures the origin locator so the Kotlin layer can offer a
    /// "Back" card after the user follows an internal cross-reference.
    ///
    /// Readium calls this delegate method before navigating to the target. The bridge captures
    /// the current position from [cachedLocatorJson] as the origin, forwards both the target
    /// href and the origin to [internalLinkCallback], then returns `true` so Readium still
    /// follows the link. The Kotlin side captures it as [NavigatorEvent.InternalLink] and
    /// shows the return-to-position card.
    func navigator(_ navigator: Navigator, shouldFollowInternalLink link: Link, referrer: Locator?) -> Bool {
        if let cb = internalLinkCallback, let origin = cachedLocatorJson {
            cb(link.href, origin)
        }
        return true
    }

    /// The selection seam.
    ///
    /// Readium-Swift has no "selection changed" delegate callback. `EditingActionsController`
    /// does, however, ask this question from its `selection` property observer every time the
    /// WKWebView reports a new selection, which makes it the one place the host learns that the
    /// user selected text. Forwarding it here is what gives iOS an annotate affordance at all.
    ///
    /// Returns `true`: Riffle's sheet is shown *alongside* the system menu rather than replacing
    /// it, so Copy / Look Up / Share keep working. Returning `false` (the documented
    /// custom-pop-up route) would suppress the system menu and silently remove three features to
    /// add one.
    func navigator(_ navigator: SelectableNavigator, shouldShowMenuForSelection selection: Selection) -> Bool {
        if let json = Self.selectionJson(locator: selection.locator, frame: selection.frame) {
            selectionCallback?(json)
        }
        return true
    }
}
