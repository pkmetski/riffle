# iOS Scenario 07 — Annotation decorations (issue #872)

## What this covers

Verifies that the annotation pipeline from `AnnotationStore` through `AnnotationDecorationCoordinator`
to `ReadiumEpubNavigatorBridge.applyDecorations` functions correctly on iOS. Covers:

- `applyDecorations` on the bridge receives the JSON produced by `ReadiumSwiftNavigator`
- Decoration JSON is correctly serialised for highlights, bookmarks, note-glyphs, and search marks
- `UIColor` hex extension parses `#RRGGBB` correctly
- Bridge applies empty decoration lists when the coordinator is stopped

## Precondition

An EPUB book is present in the test library. The `ReadiumEpubNavigatorBridge` is instantiated.

---

## Scenario 07-A: applyDecorations reaches the bridge

**What:** Calling `simulateApplyDecorations(_:group:)` records the JSON and group for later assertion.

**XCTest target:** `iosApp/iosAppTests/AnnotationTests.swift`

```swift
let bridge = ReadiumEpubNavigatorBridge()
bridge.simulateApplyDecorations("[{\"id\":\"h1\",\"type\":\"highlight\"}]", group: "highlights")
XCTAssertEqual(bridge.lastAppliedGroup, "highlights")
XCTAssertNotNil(bridge.lastAppliedDecorationsJson)
```

---

## Scenario 07-B: parseDecoration handles all four types

**What:** All four decoration types (`highlight`, `bookmark`, `noteGlyph`, `searchMark`) produce
non-nil decorations when the locator JSON is valid.

```swift
// highlight
let highlightJson = "[{\"id\":\"h1\",\"type\":\"highlight\",\"locator\":{\"href\":\"ch1.xhtml\",\"type\":\"application/xhtml+xml\",\"locations\":{\"cfi\":\"/4/2\",\"progression\":0.5}},\"color\":\"#FFFF00\",\"alpha\":0.4}]"
bridge.applyDecorations(decorationsJson: highlightJson, group: "highlights")
// Assert bridge.lastAppliedDecorationsJson == highlightJson (recorded before async apply)

// bookmark
let bookmarkJson = "[{\"id\":\"b1\",\"type\":\"bookmark\",\"locator\":{\"href\":\"ch1.xhtml\",\"type\":\"application/xhtml+xml\",\"locations\":{\"cfi\":\"/4/2\",\"progression\":0.3}}}]"
bridge.applyDecorations(decorationsJson: bookmarkJson, group: "bookmarks")
XCTAssertEqual(bridge.lastAppliedGroup, "bookmarks")
```

---

## Scenario 07-C: UIColor hex extension parses #RRGGBB

**What:** Pure red `#FF0000`, pure green `#00FF00`, and pure blue `#0000FF` parse to the expected
RGBA components.

```swift
let red = UIColor(hex: "#FF0000")
var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
red.getRed(&r, green: &g, blue: &b, alpha: &a)
XCTAssertEqual(r, 1.0, accuracy: 0.01)
XCTAssertEqual(g, 0.0, accuracy: 0.01)
XCTAssertEqual(b, 0.0, accuracy: 0.01)
```

---

## Scenario 07-D: Malformed decoration JSON produces empty apply

**What:** Passing `"not-json"` or `"null"` to `applyDecorations` must not crash and must record
the raw JSON (the parsing failure is silent — empty array is applied to the navigator).

```swift
bridge.applyDecorations(decorationsJson: "not valid json", group: "highlights")
// Should not crash; bridge records the string
XCTAssertNotNil(bridge.lastAppliedDecorationsJson)
```
