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
        let spineHrefs = pub.readingOrder.map { $0.href.string }
        let initialHref = initialLocator?.href.string ?? spineHrefs.first ?? ""
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
        continuousScrollView = csv

        // Pre-populate spine JSON from the publication so position tracking works immediately.
        prefetchSpine(pub)
    }

    func applyReaderPreferences(preferences: IosReaderPreferences) {
        currentContinuousPrefs = preferences
        pendingIsContinuousMode = preferences.isContinuousMode

        // If we have a live continuous scroll view, update its preferences in-place.
        if preferences.isContinuousMode, let csv = continuousScrollView {
            Task { @MainActor in
                csv.updatePreferences(preferences)
            }
            return
        }

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

        let epubPrefs = EPUBPreferences(
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
        pendingPreferences = epubPrefs
        Task { @MainActor in
            epubNavigator?.submitPreferences(epubPrefs)
        }
    }
}
