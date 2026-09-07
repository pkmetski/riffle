# 17 — Source Type Picker (iOS)

## 17.1 All source type cards are displayed

**Given** the user opens the "Add source" screen.
**When** the source type picker renders.
**Then** cards for Audiobookshelf, Local files, Chitanka, and Project Gutenberg are all visible.

**Coverage:** `SourceTypePickerScreenTest.allCards_areDisplayed`

## 17.2 Tapping the Gutenberg card invokes callback with GUTENBERG type

**Given** the source type picker is visible.
**When** the user taps the Project Gutenberg card.
**Then** the pick callback is invoked with `SourceType.GUTENBERG`.

**Coverage:** `SourceTypePickerScreenTest.gutenbergCard_click_invokesCallbackWithGutenbergType`

## 17.3 Tapping the Audiobookshelf card invokes callback with ABS type

**Given** the source type picker is visible.
**When** the user taps the Audiobookshelf card.
**Then** the pick callback is invoked with `SourceType.ABS`.

**Coverage:** `SourceTypePickerScreenTest.audiobookshelfCard_click_invokesCallbackWithAbsType`

## 17.4 Tapping the Local files card invokes callback with LOCAL_FILES type

**Given** the source type picker is visible.
**When** the user taps the Local files card.
**Then** the pick callback is invoked with `SourceType.LOCAL_FILES`.

**Coverage:** `SourceTypePickerScreenTest.localFilesCard_click_invokesCallbackWithLocalFilesType`

## 17.5 Source type enum values are present in KMP framework

**Given** the Riffle KMP framework is imported.
**Then** `SourceType.abs`, `SourceType.gutenberg`, `SourceType.localFiles`, and `SourceType.chitanka` enum entries are accessible and non-nil.

**Coverage:** `SourceTypeTest` (`core/models/src/jvmTest`) pins the enum values and their
classification. The former `SourcePickerTests.testSourceTypeValuesExist` XCTest merely
re-asserted the same constants from Swift and was removed.

## 17.6 Catalog grid zoom adapts to screen width

**Given** the web source catalog grid is displayed.
**When** the user pinch-zooms.
**Then** the grid zooms within the permitted bounds and does not exceed the viewport width.

**Coverage:** `UnboundedCatalogGridZoomTest` (Android).

**iOS coverage:** GAP (deferred) — WKWebView pinch-zoom cannot be asserted without an XCUITest
driving real pinch gestures on a configured web source; verified manually. The former
`SourcePickerTests.testCatalogGridZoomWithinBounds` unconditional-skip placeholder was removed.

## iOS coverage note (17.1–17.4)

The picker UI is shared Compose Multiplatform, exercised on Android by
`SourceTypePickerScreenTest`. On iOS these four scenarios are a GAP (deferred): XCTest cannot
drive Compose UI without accessibility identifiers exposed to XCUITest. The former
`SourcePickerTests` unconditional-skip placeholders (`testAllSourceTypeCardsDisplayed`,
`testGutenbergCardTapInvokesGutenbergType`, `testAbsCardTapInvokesAbsType`,
`testLocalFilesCardTapInvokesLocalFilesType`) were removed; verified manually until an
XCUITest with proper accessibility handles is added.
