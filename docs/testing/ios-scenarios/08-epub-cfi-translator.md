# Scenario 08b: EPUB CFI Translator

**Android reference:** `EpubCfiTranslatorInstrumentedTest.kt` (15 tests)  
**iOS status:** Partially covered — pure CFI math in `commonTest`; EPUB-file-reading half blocked  
**KMP source:** `core/domain/src/commonMain/kotlin/com/riffle/core/domain/EbookCfiTranslator.kt`

---

## Context

The EPUB CFI translator converts between EPUB CFI strings and reading-position values
(chapter progression fractions, element IDs). Two layers:

- **Pure CFI math** (`cfiDocPathToProgression`, `progressionToCfiDocPath`, `hasElementWithId`,
  `extractCfiElementIds`): platform-neutral Kotlin in `core/domain/commonMain`. Covered by
  `commonTest` across JVM and iOS.
- **EPUB-file implementation** (`EbookCfiTranslatorImpl`): unzips the EPUB, parses chapter HTML,
  and applies the pure functions. Android-only (`AssetManager` + `ZipFile`). No iOS equivalent
  yet.

---

## Scenarios

### 08b-A: Pure CFI functions — covered by commonTest

The following pure functions live in `core/domain/commonMain` and are testable without a live
WebView or EPUB file. Add them to `core/domain/src/commonTest/` if not already present.

- `hasElementWithId(html, id)` — finds a real section id, returns false for absent ids.
- `extractCfiElementIds(cfi)` — parses element id assertions from a CFI string.
- `cfiDocPathToProgression(html, cfi)` — progression at start of chapter is near zero;
  progression increases along the chapter; id-anchored and numeric give same result for a
  real section heading.
- `progressionToCfiDocPath(html, progression)` — round-trip on real chapter HTML is stable.

**iOS status:** These functions are in `commonMain`; iOS simulator runs `commonTest` via
`xcodebuild test -scheme iosApp -sdk iphonesimulator`. Add `core:domain` commonTest coverage
if not already present.

### 08b-B: EPUB file reading — GAP

`EpubCfiTranslatorInstrumentedTest` opens a bundled `test.epub` asset, extracts chapter HTML
via `ZipFile`, and calls the pure functions through `EbookCfiTranslatorImpl`.

**Gap reason:** No iOS implementation of `EbookCfiTranslatorImpl`. The KMP factory
`EbookCfiTranslatorFactory.forItem()` currently returns `null` on iOS (no-op path in
`ReadiumSwiftNavigator`). An iOS implementation would need `libzip` / `ZipFoundation` or
Swift's built-in compression APIs to unzip the EPUB and read chapter HTML.

**Acceptance when implemented:**
- `IosEbookCfiTranslatorImpl` can open a bundled `test.epub` from `Bundle.main`.
- `progressionAtStartOfChapter` is < 0.01.
- `roundTripOnRealChapterHtml` CFI encodes and decodes stably.

---

## Blocking conditions

- An iOS implementation of `EbookCfiTranslatorImpl` (Readium-Swift can provide the HTML
  through its `Publication` API, which avoids direct zip extraction).
- Once implemented, add an XCTest class `EpubCfiTranslatorTests.swift` in `iosAppTests/`
  mirroring the 15 Android instrumented scenarios.
