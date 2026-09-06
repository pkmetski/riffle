# Scenario 08e: Annotation Focus Harness (scroll-to-annotation in reader)

**Android reference:** `AnnotationFocusHarnessTest.kt` (6 tests)  
**iOS status:** GAP — full reader E2E with annotation DB wiring not yet available on iOS  
**Android location:** `app/src/androidTest/kotlin/com/riffle/app/feature/reader/`

---

## Context

`AnnotationFocusHarnessTest` opens a real EPUB in the reader, creates annotations via the
annotation controller, then verifies that calling `scrollToAnnotation(id)` (or its trigger
from the annotations list) actually centres the annotated passage on screen.

The test exercises the full stack:
- DB write → `AnnotationRepository` → `AnnotationController.focusAnnotation(id)`
- `EpubReaderScreen` observes `focusedAnnotationId` state
- JS decoration placement + `autoScrollToAnnotation` JS call on the correct CFI range
- `window.scrollY` / `scrollLeft` correctly targeting the annotated element

---

## Scenarios (all deferred)

### 08e-A: Focus bookmark scrolls to its CFI
- Create a bookmark at a known CFI (chapter 2, mid-page). Open the book.
- Call `AnnotationController.focusAnnotation(bookmarkId)`.
- Assert `window.scrollY` places the bookmark element in the visible viewport.

### 08e-B: Focus highlight scrolls to its CFI range start
- Create a text highlight spanning 3 sentences. Focus it.
- Assert the highlight's start element is visible.

### 08e-C: Focus annotation in a different chapter navigates and scrolls
- Create annotation in chapter 3. Open the book at chapter 1.
- Focus the chapter-3 annotation.
- Assert the reader navigated to chapter 3 and the element is visible.

### 08e-D: Second focus on same annotation is a no-op scroll (idempotent)
- Focus annotation once → assert on screen. Focus again.
- Assert `window.scrollY` does not change by more than 5 px.

### 08e-E: Annotation created in continuous mode is focusable in paginated mode
- Create annotation in continuous scroll mode.
- Switch to paginated. Focus the annotation.
- Assert the annotated column is the current page.

### 08e-F: Focus annotation with stale (deleted) ID is a graceful no-op
- Delete an annotation from the DB. Attempt to focus its now-invalid ID.
- Assert no crash and reader state is unchanged.

---

**Gap reason:** All 6 scenarios require:
1. A full reader session open (WKWebView + Readium Swift navigator loaded with EPUB content).
2. The annotation DB wired into the iOS KMP module.
3. `AnnotationController` emitting `focusedAnnotationId` observable to SwiftUI.
4. The iOS equivalent of `scrollToAnnotation` JS path implemented (currently a no-op stub).

The iOS reader presents the EPUB via `EPUBNavigatorViewController`; annotation focus would
need to call `EPUBNavigatorViewController.go(locator:)` and then evaluate the same
`autoScrollToAnnotation` JS used on Android. Neither the DB wiring nor the JS injection for
annotation focus exists on iOS yet.

**Acceptance when implemented:**
- `AnnotationController.focusAnnotation(id)` on iOS triggers `EPUBNavigatorViewController.go(locator:)`
  and injects `scrollToAnnotation` JS.
- `AnnotationFocusTests.swift` verifies all 6 scenarios in a UI test using a bundled test EPUB.
- Update this scenario doc.

**Work needed:**
1. Wire `AnnotationRepository` to iOS KMP module (Room-based on Android; needs SQLDelight or
   direct KMP DB access on iOS).
2. Implement `focusAnnotation` callback in `ReadiumSwiftNavigator`.
3. Evaluate `autoScrollToAnnotation` JS after navigator arrives at the locator.
4. Add `AnnotationFocusTests.swift` as an `XCTestCase` UI test.
5. Update this scenario doc.
