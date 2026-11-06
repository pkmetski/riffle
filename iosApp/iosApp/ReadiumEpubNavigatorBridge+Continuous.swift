import UIKit
import Riffle
import ReadiumShared
import ReadiumNavigator

// MARK: - Continuous mode attachment and preferences
//
// In a separate file rather than the class body to keep ReadiumEpubNavigatorBridge.swift
// under SwiftLint's file_length and type_body_length limits.

extension ReadiumEpubNavigatorBridge {

    @MainActor
    func attachContinuous(publication pub: Publication, initialLocator: Locator?) {
        let spineHrefs = pub.readingOrder.map { $0.href }
        // locator.href.string mirrors what locationDidChange stores in lastLoadedHref and may be
        // relative or absolute depending on the Readium version. stripOrigin handles both: for a
        // relative path the regex simply does not match and the string is returned as-is.
        let rawHref = initialLocator.map { $0.href.string } ?? spineHrefs.first ?? ""
        let initialHref = ReadiumEpubNavigatorBridge.stripOrigin(from: rawHref)
        let initialProgression = Float(initialLocator?.locations.progression ?? 0)
        let prefs = currentContinuousPrefs ?? IosReaderPreferences(
            fontSizePercent: 1.0, scrollMode: true, theme: "light", fontFamilyCss: "",
            lineHeightMultiplier: 1.5, pageMargins: 1.0, justifyText: false,
            textColorArgb: 0, publisherStyles: false, columnCount: 1, isContinuousMode: true
        )

        let csv = IosContinuousScrollView()
        csv.frame = hostViewController.view.bounds
        csv.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        hostViewController.view.addSubview(csv)

        csv.onLocator = { [weak self] json in
            self?.cachedLocatorJson = json
            self?.locatorCallback?(json)
        }
        csv.onPageLoad = { [weak self] in
            self?.pageLoadCallback?()
        }
        csv.onScrollStart = { [weak self] in
            self?.scrollStartCallback?()
        }
        csv.onTap = { [weak self] point in
            guard let self else { return }
            let viewW = Double(self.hostViewController.view.bounds.width)
            let viewH = Double(self.hostViewController.view.bounds.height)
            self.tapCallback?(TapCoords(
                x: Double(point.x), y: Double(point.y),
                viewWidth: viewW, viewHeight: viewH
            ))
        }

        csv.initialize(
            publication: pub,
            spineHrefs: spineHrefs,
            initialHref: initialHref,
            initialProgression: initialProgression,
            prefs: prefs
        )

        // Cold-open fast path: load chapter cache off the main thread so disk I/O doesn't block
        // first paint, then prime the slot on main once the read completes.
        let fingerprint = iosReaderPrefsFingerprint(prefs)
        let capturedItemId = cachedItemId
        let capturedCsv = csv
        Task.detached(priority: .userInitiated) { [cache = self.chapterCache] in
            let entry = cache.load(itemId: capturedItemId, chapterHref: initialHref, prefsFingerprint: fingerprint)
            if let entry {
                await MainActor.run {
                    capturedCsv.primeWithCachedHeight(href: initialHref, cachedData: entry.data, heightPx: Int(entry.heightPx))
                }
            }
        }

        continuousScrollView = csv

        // Write path: on background, save the current chapter's bytes + height so the next
        // cold open can serve from cache without reading from the EPUB zip.
        NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.saveContinuousCacheOnBackground()
        }

        // Pre-populate spine JSON from the publication so position tracking works immediately.
        prefetchSpine(pub)
    }

    @MainActor func saveContinuousCacheOnBackground() {
        guard let csv = continuousScrollView,
              let href = csv.currentChapterHref,
              let prefs = currentContinuousPrefs,
              let heightPx = csv.measuredHeight(forHref: href) else { return }
        let fingerprint = iosReaderPrefsFingerprint(prefs)
        // Re-derive the transformed XHTML from the scheme handler (needs publication still open).
        guard let pub = publication else { return }
        let normalizedHref = href.components(separatedBy: "#").first ?? href
        let allLinks = pub.readingOrder + pub.resources
        guard let link = allLinks.first(where: { normalizeHref($0.href) == normalizeHref(normalizedHref) }),
              let resource = pub.get(link) else { return }
        Task { @MainActor [weak self] in
            guard let self else { return }
            guard case .success(let rawData) = await resource.read(),
                  let rawHtml = String(data: rawData, encoding: .utf8) ?? String(data: rawData, encoding: .isoLatin1),
                  let prefs = self.currentContinuousPrefs else { return }
            let transformed = IosContinuousCssInjector.injectInto(html: rawHtml, href: normalizedHref, prefs: prefs)
            guard let xhtmlData = transformed.data(using: .utf8) else { return }
            self.chapterCache.save(
                itemId: self.cachedItemId,
                chapterHref: normalizedHref,
                prefsFingerprint: iosReaderPrefsFingerprint(prefs),
                xhtmlData: xhtmlData,
                heightPx: Int(heightPx)
            )
        }
    }

    private func normalizeHref(_ href: String) -> String {
        let stripped = href.hasPrefix("/") ? String(href.dropFirst()) : href
        return stripped.components(separatedBy: "#").first ?? stripped
    }

    func applyReaderPreferences(preferences: IosReaderPreferences) {
        currentContinuousPrefs = preferences
        pendingIsContinuousMode = preferences.isContinuousMode

        if preferences.isContinuousMode {
            if let csv = continuousScrollView {
                // Already in continuous mode — update preferences in-place.
                Task { @MainActor in csv.updatePreferences(preferences) }
                return
            }
            // Switching from non-continuous → continuous while a publication is open.
            if let pub = publication {
                Task { @MainActor in
                    let initialLocator = self.captureCurrentLocator()
                    self.tearDownEpubNavigator()
                    self.attachContinuous(publication: pub, initialLocator: initialLocator)
                }
                return
            }
            // No publication yet — pendingIsContinuousMode recorded above; attach() will honour it.
            return
        }

        // preferences.isContinuousMode == false
        if let csv = continuousScrollView {
            // Switching from continuous → non-continuous while a publication is open.
            if let pub = publication {
                let epubPrefs = buildEpubPreferences(from: preferences)
                pendingPreferences = epubPrefs
                Task { @MainActor in
                    let initialLocator = self.captureCurrentLocator()
                    csv.removeFromSuperview()
                    self.continuousScrollView = nil
                    self.lastLoadedHref = nil
                    try? self.attach(publication: pub, initialLocator: initialLocator)
                    // pendingPreferences was already set to epubPrefs before this Task was created;
                    // makeNavigatorConfiguration() picks it up so submitPreferences is redundant here.
                    // Calling submitPreferences again can trigger Readium's spread reload, resetting position.
                }
                return
            }
        }

        // Already in non-continuous mode — update preferences in-place.
        let epubPrefs = buildEpubPreferences(from: preferences)
        pendingPreferences = epubPrefs
        Task { @MainActor in
            epubNavigator?.submitPreferences(epubPrefs)
        }
    }

    // MARK: - Helpers

    @MainActor
    private func captureCurrentLocator() -> Locator? {
        if continuousScrollView != nil {
            // Continuous mode: locator JSON comes from makeLocatorJson (relative hrefs). Parse it.
            guard let json = cachedLocatorJson,
                  let jsonValue = try? JSONValue(jsonString: json) else { return nil }
            return try? Locator(json: jsonValue, warnings: nil)
        } else {
            // Non-continuous mode: return the cached Locator directly — no JSON round-trip.
            // Mirrors Android's PositionOrchestrator.snapshotLastLocator(); avoids losing position
            // when locator.jsonString() fails transiently (which would leave cachedLocatorJson nil).
            return cachedLocator
        }
    }

    @MainActor
    private func tearDownEpubNavigator() {
        epubNavigator?.willMove(toParent: nil)
        epubNavigator?.view.removeFromSuperview()
        epubNavigator?.removeFromParent()
        epubNavigator = nil
        lastLoadedHref = nil
    }

    private func buildEpubPreferences(from preferences: IosReaderPreferences) -> EPUBPreferences {
        // Theme strings are owned by the Kotlin layer (IosEpubReaderScreen.kt).
        // This switch is a Readium-Swift type adapter only — move any string-value logic there.
        let resolvedTheme: Theme? = switch preferences.theme {
        case "dark": .dark
        case "sepia": .sepia
        default: .light
        }

        var fontFamily: FontFamily?
        if !preferences.fontFamilyCss.isEmpty {
            fontFamily = FontFamily(rawValue: preferences.fontFamilyCss)
        }

        let textAlign: TextAlignment? = preferences.justifyText ? .justify : nil
        let lineHeight: Double? = preferences.lineHeightMultiplier > 0 ? Double(preferences.lineHeightMultiplier) : nil

        // 0 means "leave it to the theme". Non-zero only for DarkDim, whose muted body colour is
        // the only thing distinguishing it from Dark — without this it renders as plain Dark.
        let textColor: ReadiumNavigator.Color? = preferences.textColorArgb != 0
            ? ReadiumNavigator.Color(uiColor: UIColor(argb: preferences.textColorArgb))
            : nil
        // 0 means "Readium's default". Android pins one column because Readium 3.3.0's
        // two-column default mispositions decorations.
        let columns: ColumnCount? = switch preferences.columnCount {
        case 1: .one
        case 2: .two
        default: nil
        }

        return EPUBPreferences(
            columnCount: columns,
            fontFamily: fontFamily,
            fontSize: Double(preferences.fontSizePercent),
            lineHeight: lineHeight,
            pageMargins: preferences.pageMargins > 0 ? preferences.pageMargins : nil,
            publisherStyles: preferences.publisherStyles,
            scroll: preferences.scrollMode,
            textAlign: textAlign,
            textColor: textColor,
            theme: resolvedTheme
        )
    }
}

// File-private overload so internal call sites can pass a prefs object directly.
private func iosReaderPrefsFingerprint(_ prefs: IosReaderPreferences) -> String {
    iosReaderPrefsFingerprint(IosReaderPrefsSnapshot(
        fontSizePercent: prefs.fontSizePercent,
        theme: prefs.theme,
        fontFamilyCss: prefs.fontFamilyCss,
        lineHeightMultiplier: prefs.lineHeightMultiplier,
        pageMargins: prefs.pageMargins,
        justifyText: prefs.justifyText,
        textColorArgb: prefs.textColorArgb,
        publisherStyles: prefs.publisherStyles,
        columnCount: prefs.columnCount
    ))
}
