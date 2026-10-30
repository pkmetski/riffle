import XCTest

// Base class for harness tests that need a pre-configured ABS source. Mirrors the pattern of
// Android's GoldenTraceHarnessTest: starts the stub server, resets app state and seeds the source
// via launch arguments (the app runs its production authenticate → commit path, only the UI is
// skipped), leaving each test with the library home already on screen. The add-source UI itself is
// covered end-to-end by AddAbsSourceFlowTests / AddKomgaSourceFlowTests.
class AbsHarnessTestCase: XCTestCase {

    var app: XCUIApplication!
    var absServer: StubAbsServer!

    override func setUpWithError() throws {
        continueAfterFailure = false
        absServer = StubAbsServer()
        absServer.start()

        app = XCUIApplication()
        app.launchArguments += [
            "--RIFFLE_RESET_FOR_TESTS",
            seedSourceArgument(type: "ABS", url: absServer.baseUrl, username: "testuser", password: "test")
        ]
        app.launch()

        waitForSeededLibraryHome(in: app, sourceName: "Audiobookshelf")
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
        absServer.shutdown()
        absServer = nil
    }
}

// Base class for harness tests that need a pre-configured Komga source.
class KomgaHarnessTestCase: XCTestCase {

    var app: XCUIApplication!
    var komgaServer: StubKomgaServer!

    override func setUpWithError() throws {
        continueAfterFailure = false
        komgaServer = StubKomgaServer()
        komgaServer.start()

        app = XCUIApplication()
        app.launchArguments += [
            "--RIFFLE_RESET_FOR_TESTS",
            seedSourceArgument(type: "KOMGA", url: komgaServer.baseUrl, username: "test@test.test", password: "test")
        ]
        app.launch()

        waitForSeededLibraryHome(in: app, sourceName: "Komga")
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
        komgaServer.shutdown()
        komgaServer = nil
    }
}

// Launch argument understood by RiffleApp (see TestSourceSeed.kt): --RIFFLE_SEED_SOURCE=<type>|<url>|<user>|<password>.
func seedSourceArgument(type: String, url: String, username: String, password: String) -> String {
    "--RIFFLE_SEED_SOURCE=\(type)|\(url)|\(username)|\(password)"
}

// The seeded source must land the app on the library home (burger menu) — never on the source picker.
func waitForSeededLibraryHome(in app: XCUIApplication, sourceName: String) {
    // Match by accessibilityIdentifier (testTag) so this works in any locale — "Open menu" becomes
    // "Отворете менюто" in Bulgarian and would never match app.buttons["Open menu"].
    let burger = app.descendants(matching: .any).matching(identifier: RiffleTestTags.navDrawerToggle).firstMatch
    // Cold launch + seeded-source install + first library load on a 3-core CI runner shared between
    // two simulator clones. 60s was marginal before #1066 grew the harness to 39 tests; 150s proved
    // too tight once two heavy suites (ProgressPipelineTests + AudiobookPlayerTests) landed on both
    // clones simultaneously — observed load time reached ~120s, leaving only 30s of headroom. 250s
    // gives 130s of headroom while still catching a genuinely hung app well within the 50-min job
    // budget.
    if !burger.waitForExistence(timeout: 250) {
        XCTFail("Seeded \(sourceName) source must land on the library home; picker visible: \(app.staticTexts["Add source"].exists)")
    }
}

// Returns the form's Connect button, making it visible first if the software keyboard hides it.
// The iOS root applies safeDrawingPadding (which includes the IME inset), so while a field has
// focus the form is squeezed and the button below the password field can be clipped out of both
// the viewport and the accessibility tree. Dismiss the keyboard, then scroll the form as a fallback.
func revealConnectButton(in app: XCUIApplication, timeout: TimeInterval = 10) -> XCUIElement {
    let connect = app.buttons["Connect"]
    if connect.waitForExistence(timeout: 2) && connect.isHittable { return connect }
    dismissKeyboard(in: app)
    if connect.waitForExistence(timeout: 3) && connect.isHittable { return connect }
    // Scroll the (vertically scrollable) form so the button enters the visible viewport.
    let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3))
    let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.05))
    start.press(forDuration: 0.05, thenDragTo: end)
    _ = connect.waitForExistence(timeout: timeout)
    return connect
}

// Hides the software keyboard if it is showing. Compose clears text-field focus when the user
// taps a non-focusable area; the top-bar title region is always such an area on the add-source
// form. Falls back to the keyboard's return key.
func dismissKeyboard(in app: XCUIApplication) {
    guard app.keyboards.firstMatch.exists else { return }
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).tap()
    if app.keyboards.firstMatch.exists {
        let returnKey = app.keyboards.buttons["return"].firstMatch
        if returnKey.exists { returnKey.tap() }
    }
    _ = XCTWaiter.wait(
        for: [XCTNSPredicateExpectation(predicate: NSPredicate(format: "count == 0"), object: app.keyboards)],
        timeout: 3
    )
}

// MARK: - Library → reader helpers

// Waits for a book tile, first on the tab that is showing (normally Home), then on the All Books
// tab. Home's section grids preview only `columns × 2 − 1` books per section and the stub seeds
// more than that, so a seeded book can legitimately sit behind the "+N more" tile — and which one
// does depends on a tie-broken sort. All Books lists every book, so it is the authoritative place.
func waitForLibraryTile(_ tile: XCUIElement, in app: XCUIApplication, timeout: TimeInterval = 10) -> Bool {
    if tile.waitForExistence(timeout: timeout) { return true }
    let allBooks = app.buttons["All Books"].firstMatch
    guard allBooks.waitForExistence(timeout: 5) else { return false }
    allBooks.tap()
    return tile.waitForExistence(timeout: timeout)
}

// Section rows are horizontal LazyRows: a tile past the right edge exists in the accessibility
// tree, but XCUITest cannot compute a hit point for it — even asking `isHittable` fails the test.
// Decide from the reported frame instead and drag the row until the tile is fully on screen.
func revealTile(_ tile: XCUIElement, in app: XCUIApplication) {
    guard tile.exists else {
        XCTFail("revealTile: tile does not exist in the accessibility tree — fixture missing or library not loaded")
        return
    }
    let screen = app.frame
    for _ in 0..<6 {
        let frame = tile.frame
        let rowY = frame.midY / screen.height
        let dragLeft: Bool
        if frame.maxX > screen.maxX {
            dragLeft = true
        } else if frame.minX < screen.minX {
            dragLeft = false
        } else {
            return
        }
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: dragLeft ? 0.9 : 0.1, dy: rowY))
        let target = app.coordinate(withNormalizedOffset: CGVector(dx: dragLeft ? 0.2 : 0.8, dy: rowY))
        from.press(forDuration: 0.1, thenDragTo: target)
    }
}

// Opens a book from a library tile the way a user does: tile → item detail → Read → reader
// (or audiobook player). Returns the reader's back control. The EPUB/PDF/CBZ readers render
// "← Back" as a clickable text, which XCUITest exposes as a Button; the audiobook player renders
// the shared Material back arrow, whose accessibility label is "Back". The item detail screen
// shows neither, so the Read button vanishing is what proves the reader opened.
@discardableResult
func openReader(from tile: XCUIElement, in app: XCUIApplication, timeout: TimeInterval = 150) -> XCUIElement {
    // Matches either "Read" (ebooks) or "Listen" (audiobook-only items). The shared
    // LibraryItemDetailScreen shows "Listen" for audiobook-only ABS items (#1145 gap 4.1);
    // both labels navigate away from the detail screen so the post-tap nonexistence check applies
    // to whichever one was found.
    let openAction = app.buttons.matching(
        NSPredicate(format: "label == 'Read' OR label == 'Listen'")
    ).firstMatch
    // Re-reveal the tile before EVERY tap, not just once up front. Two things make a single reveal
    // unreliable: a tap that lands while the LazyRow is still settling is swallowed as a scroll
    // stop, and after re-entering the library (e.g. reopening a book) the target tile is often
    // scrolled past the right edge — so a retry that taps the original position hits an offscreen
    // spot and never opens the detail screen (the reopen-flake root cause). Stop as soon as the
    // detail screen appears, or once the tap has navigated the tile out of the library.
    for attempt in 0..<3 {
        if openAction.exists { break }
        revealTile(tile, in: app)
        guard tile.exists else { break }
        waitForStableFrame(of: tile)
        tile.tap()
        if openAction.waitForExistence(timeout: attempt == 0 ? 5 : 30) { break }
    }
    // A final settle wait covers the case where the last tap navigated but the loaded runner is
    // still rendering the item detail. 60 s: on loaded simulator clones item-detail rendering
    // can exceed 30 s; 60 s covers the observed worst-case.
    XCTAssertTrue(openAction.waitForExistence(timeout: 60), "Item detail must show the Read or Listen action")
    openAction.tap()
    XCTAssertTrue(openAction.waitForNonExistence(timeout: timeout), "Read/Listen must leave the item detail screen")
    let back = app.buttons.matching(
        NSPredicate(format: "label == '← Back' OR label == 'Back'")
    ).firstMatch
    XCTAssertTrue(back.waitForExistence(timeout: timeout), "Reader must show its back control")
    return back
}

// Blocks until the element's frame stops moving (a LazyRow fling settling after a drag).
func waitForStableFrame(of element: XCUIElement, timeout: TimeInterval = 3) {
    let deadline = Date().addingTimeInterval(timeout)
    var last = element.frame
    while Date() < deadline {
        Thread.sleep(forTimeInterval: 0.2)
        let now = element.frame
        if now.equalTo(last) { return }
        last = now
    }
}

// Taps a reader/player back control and waits for the library home to re-render. On a loaded CI
// runner a single back tap can be swallowed while the screen is still settling, leaving the app on
// the reader so the library home never appears; retry the tap once before failing. Same swallowed-
// tap hazard the openReader reveal loop guards against, on the return leg.
func tapBackToLibrary(_ back: XCUIElement, in app: XCUIApplication,
                      file: StaticString = #filePath, line: UInt = #line) {
    back.tap()
    if waitForLibraryHome(in: app, timeout: 45) { return }
    // The first tap may have been swallowed (reader still visible), or the reader may have popped
    // to item-detail which itself needs one more back to reach library home (two-level nav).
    // Re-query with a fresh reference and a settle wait to cover both scenarios reliably.
    // 20 s for the retry button: under heavy CI clone load the ItemDetail screen can take longer
    // than 5 s to settle after the reader pops — and if the button isn't found, the second back
    // never fires, leaving us on the reader and the final 90 s wait also times out.
    let retryBack = app.buttons.matching(
        NSPredicate(format: "label == '← Back' OR label == 'Back'")
    ).firstMatch
    // 60 s: under CI clone memory pressure the ItemDetail screen can take >30 s to settle after
    // the reader pops — if the button isn't found the second back never fires. Increased from 30 s
    // after a CI failure where item detail appeared at ~31 s, causing the retry to be skipped.
    if retryBack.waitForExistence(timeout: 60) { retryBack.tap() }
    // 120 s: two-level navigation (reader → ItemDetail → library) under CI clone contention.
    // waitForLibraryHome now checks the burger button first (fast path, data-independent), so
    // the full budget is rarely consumed; the cap guards against extreme load spikes.
    XCTAssertTrue(waitForLibraryHome(in: app, timeout: 120),
                  "Tapping back from the reader should return to library home", file: file, line: line)
}

// True once the app is back on the library home screen.
//
// Two-phase detection:
// 1. Fast path: the "Open menu" burger button is part of the library's top-bar composition and
//    appears as soon as the screen renders, well before the network response populates any section.
//    Under CI clone memory pressure the section headers can take >90 s to appear even after the
//    screen is visible — waiting for them exclusively causes reliable false-timeouts. The burger
//    button is absent on item-detail, readers, and settings screens so it is a safe discriminator.
// 2. Section headers: the pre-existing check kept as a fallback for cases where the burger button
//    label doesn't match (locale/accessibility overrides). Uses BEGINSWITH so the count suffix
//    added in #1140 ("In Progress (3)") still matches.
func waitForLibraryHome(in app: XCUIApplication, timeout: TimeInterval = 120) -> Bool {
    // Fast path: nav-drawer toggle (stable testTag, locale-independent) = library tab visible.
    // Matching by accessibilityIdentifier avoids "Open menu" → "Отворете менюто" locale drift.
    let burger = app.descendants(matching: .any).matching(identifier: RiffleTestTags.navDrawerToggle).firstMatch
    let fastTimeout = min(timeout, 15)
    if burger.waitForExistence(timeout: fastTimeout) { return true }

    // Fallback: wait for any section header for the remainder of the timeout budget.
    let remaining = max(timeout - fastTimeout, 0)
    guard remaining > 0 else { return false }
    let sectionIdentifiers = [
        "section-header-IN_PROGRESS", "section-header-RECENTLY_ADDED",
        "section-header-FINISHED", "section-header-CONTINUE_SERIES",
    ]
    let anySection = NSPredicate { _, _ in
        sectionIdentifiers.contains { id in
            app.descendants(matching: .any).matching(identifier: id).firstMatch.exists
        }
    }
    let result = XCTWaiter.wait(
        for: [XCTNSPredicateExpectation(predicate: anySection, object: nil)],
        timeout: remaining
    )
    return result == .completed
}
