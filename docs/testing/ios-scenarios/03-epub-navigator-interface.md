# iOS Scenario 03 — EpubNavigatorInterface seam (issue #866)

## What this covers

Verifies that the `EpubNavigatorInterface` KMP contract (defined in `feature:reader/commonMain`)
matches expectations for an iOS-side implementation. These scenarios are written against the
interface contract; the concrete iOS implementation will live in `iosApp/` as part of issue #869.

## Precondition

An EPUB book is present in the test library (any title works).

---

## Scenario 1: `NavigatorTypes` value semantics

**What:** `NavigatorPosition`, `NavigatorDecoration`, and `NavigatorScrollBoundary` are pure data
types with no platform-specific dependencies. Verify their equality and defaults from Swift.

**XCTest target:** `iosApp/iosAppTests/NavigatorTypesTests.swift`

```swift
// NavigatorPosition equality
let posA = NavigatorPosition(href: "ch1.xhtml", progression: 0.5, totalProgression: nil, locatorJson: "{}")
let posB = NavigatorPosition(href: "ch1.xhtml", progression: 0.5, totalProgression: nil, locatorJson: "{}")
XCTAssertEqual(posA, posB)

// NavigatorScrollBoundary.None defaults
let boundary = NavigatorScrollBoundary.companion.None
XCTAssertFalse(boundary.atForwardBoundary)
XCTAssertFalse(boundary.atBackwardBoundary)

// NavigatorNavigationOptions defaults
let opts = NavigatorNavigationOptions()
XCTAssertTrue(opts.snap)
XCTAssertTrue(opts.landAtStartWhenNoTarget)
XCTAssertFalse(opts.snapProgressionToNearestColumn)
XCTAssertTrue(opts.animated)
XCTAssertFalse(opts.alignToTop)
XCTAssertNil(opts.focusAnnotationId)
```

**Expected:** All assertions pass; no crash; no missing Kotlin/Native symbols.

---

## Scenario 2: `FakeEpubNavigator` (iOS equivalent) contract

**What:** An iOS `FakeEpubNavigator` conforming to `EpubNavigatorInterface` must compile and
record calls identically to the JVM fake.

**XCTest target:** `iosApp/iosAppTests/FakeEpubNavigatorTests.swift`

```swift
let fake = FakeEpubNavigatorIOS()  // iOS test double — to be implemented with #869

// open() records path
await fake.open(bookFilePath: "/tmp/test.epub", initialLocatorJson: nil)
XCTAssertEqual(fake.openedPath, "/tmp/test.epub")
XCTAssertNil(fake.openedLocatorJson)

// close() sets closed flag
fake.close()
XCTAssertTrue(fake.closed)

// navigateTo() records call
let target = NavigatorNavigationTarget.ToHref(href: "ch2.xhtml", fragment: nil)
await fake.navigateTo(target: target, options: NavigatorNavigationOptions())
XCTAssertEqual(fake.navigateCalls.count, 1)
```

**Expected:** All assertions pass; `FakeEpubNavigatorIOS` compiles against the KMP-generated
Swift API for `EpubNavigatorInterface`.

---

## Scenario 3: `NavigatorFollowResult` enum completeness

**What:** Verify all three enum cases are accessible from Swift.

```swift
let values: [NavigatorFollowResult] = [.snapped, .offPage, .unavailable]
XCTAssertEqual(values.count, 3)
```

**Expected:** No missing case warning; all three values distinct.

---

## Notes for implementer (#869)

- `EpubNavigatorInterface` is in `feature:reader/commonMain`; its Kotlin/Native export will be
  in the `RiffleFeatureReader` framework.
- `LocatorJson` is a `typealias` for `String` — from Swift it appears as `String`.
- `NavigatorPageLoad` is a value class (`@JvmInline value class NavigatorPageLoad(val generation: Int)`);
  from Swift it maps to `Int32` (the boxed form); wrap it in a `NavigatorPageLoad` init in the
  Swift layer.
- `applyDecorations` decoration bridge is deferred; no-op implementations are acceptable for #869.
- `applyHighlightDomPatch` and `search` are also deferred; return empty/throw in the iOS impl.
