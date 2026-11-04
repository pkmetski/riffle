import Foundation

// MARK: - IosReaderChapterCache

/// Single-entry disk cache for the current chapter's pre-transformed XHTML and measured height.
/// Mirrors Android's `ReaderChapterCache` — same `meta.json` + `body.bin` layout, same atomic
/// tmp-rename write so a crash during save never leaves a corrupt entry.
///
/// Cache key: `itemId + chapterHref + prefsFingerprint`.
/// One write per session (on background / reader close), one read on cold open.
final class IosReaderChapterCache {

    private let dir: URL

    init(cacheDir: URL) {
        self.dir = cacheDir.appendingPathComponent("reader_chapter", isDirectory: true)
    }

    // MARK: - Save

    func save(itemId: String, chapterHref: String, prefsFingerprint: String, xhtmlData: Data, heightPx: Int) {
        let fm = FileManager.default
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)

        let meta = IosChapterCacheMeta(
            itemId: itemId,
            chapterHref: normalizeHref(chapterHref),
            prefsFingerprint: prefsFingerprint,
            heightPx: heightPx
        )
        guard let metaData = try? JSONEncoder().encode(meta) else { return }

        let metaTmp = dir.appendingPathComponent("meta.tmp")
        let metaFinal = dir.appendingPathComponent("meta.json")
        let bodyTmp = dir.appendingPathComponent("body.tmp")
        let bodyFinal = dir.appendingPathComponent("body.bin")

        do {
            try metaData.write(to: metaTmp, options: .atomic)
            try xhtmlData.write(to: bodyTmp, options: .atomic)
            _ = try? fm.replaceItemAt(metaFinal, withItemAt: metaTmp)
            _ = try? fm.replaceItemAt(bodyFinal, withItemAt: bodyTmp)
        } catch {
            // Non-fatal: cold reopen will simply show the normal loading path.
        }
    }

    // MARK: - Load

    func load(itemId: String, chapterHref: String, prefsFingerprint: String) -> (data: Data, heightPx: Int)? {
        let metaFile = dir.appendingPathComponent("meta.json")
        let bodyFile = dir.appendingPathComponent("body.bin")

        guard let metaData = try? Data(contentsOf: metaFile),
              let meta = try? JSONDecoder().decode(IosChapterCacheMeta.self, from: metaData) else {
            return nil
        }
        guard meta.itemId == itemId,
              meta.chapterHref == normalizeHref(chapterHref),
              meta.prefsFingerprint == prefsFingerprint else {
            return nil
        }
        guard let body = try? Data(contentsOf: bodyFile) else { return nil }
        return (body, meta.heightPx)
    }

    // MARK: - Helpers

    private func normalizeHref(_ href: String) -> String {
        href.components(separatedBy: "#").first ?? href
    }
}

// MARK: - IosChapterCacheMeta

private struct IosChapterCacheMeta: Codable {
    let itemId: String
    let chapterHref: String
    let prefsFingerprint: String
    let heightPx: Int
}

// MARK: - Prefs fingerprint

/// Stable djb2-style hash of the formatting prefs that affect XHTML output.
/// Used as a cache key alongside itemId + chapterHref to detect pref changes.
func iosReaderPrefsFingerprint(_ prefs: IosReaderPreferences) -> String {
    let raw = "\(prefs.fontSizePercent)|\(prefs.theme)|\(prefs.fontFamilyCss)|\(prefs.lineHeightMultiplier)|\(prefs.pageMargins)|\(prefs.justifyText)|\(prefs.textColorArgb)|\(prefs.publisherStyles)|\(prefs.columnCount)"
    var hash: UInt32 = 5381
    for byte in raw.utf8 {
        hash = hash &* 31 &+ UInt32(byte)
    }
    return String(format: "%08x", hash)
}
