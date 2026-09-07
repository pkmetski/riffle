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

**What:** The real `applyDecorations(decorationsJson:group:)` records the JSON and group it was
handed before hopping to the main actor to hand the parsed decorations to Readium. The coordinator
reads that record back to decide whether a group needs re-applying.

**XCTest target:** `iosApp/iosAppTests/AnnotationTests.swift`

```swift
bridge.applyDecorations(decorationsJson: highlightJson, group: "highlights")
XCTAssertEqual(bridge.lastAppliedGroup, "highlights")
XCTAssertEqual(bridge.lastAppliedDecorationsJson, highlightJson)
```

Covered by `AnnotationTests.testApplyHighlightDecorationsRecordsJson` and
`testApplyEmptyListRecordsEmptyJson`. The former `testSimulateApplyDecorationsRecordsJsonAndGroup`
asserted only that the test-only `simulateApplyDecorations` seam echoed its own arguments back —
a tautology that could not fail — so both the test and the now-unused seam were removed.

---

## Scenario 07-B: parseDecorations handles all four types

**What:** All four decoration types the `AnnotationDecorationCoordinator` emits (`highlight`,
`bookmark`, `noteGlyph`, `searchMark`) parse to exactly one `Decoration`, keeping the id Readium
needs to replace it. A type that falls through to `default` is dropped silently: the annotation
stays in the database and in the annotations list but never renders in the reader. A decoration
missing its `locator` must likewise be dropped rather than rendered at an arbitrary position.

```swift
for type in ["highlight", "bookmark", "noteGlyph", "searchMark"] {
    let decorations = bridge.parseDecorations(json(forType: type, id: "d1"))
    XCTAssertEqual(decorations.count, 1)
    XCTAssertEqual(decorations.first?.id, "d1")
}
XCTAssertTrue(bridge.parseDecorations("[{\"id\":\"h1\",\"type\":\"highlight\"}]").isEmpty)
```

Covered by `AnnotationTests.testAllFourDecorationTypesParse` and
`testDecorationWithoutLocatorIsDropped`.

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

**What:** Malformed decoration JSON must parse to zero decorations (so nothing bogus is applied
to the navigator), while a well-formed decoration list parses to the expected count — the
positive control that proves the parser is not simply always-empty.

```swift
XCTAssertEqual(bridge.parseDecorations(validHighlightJson).count, 1)   // positive control
XCTAssertTrue(bridge.parseDecorations("not valid json").isEmpty)
XCTAssertTrue(bridge.parseDecorations("null").isEmpty)
XCTAssertTrue(bridge.parseDecorations(unknownTypeJson).isEmpty)        // unknown type dropped
```

Covered by `AnnotationTests.testValidJsonParsesToDecorations`,
`testMalformedJsonParsesToZeroDecorations`, `testNullJsonParsesToZeroDecorations`, and
`testDecorationWithUnknownTypeIsDropped`.
