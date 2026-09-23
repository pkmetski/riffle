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
    let burger = app.buttons["Open menu"]
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

// Section rows are horizontal LazyRows: a tile past the right edge exists in the accessibility
// tree, but XCUITest cannot compute a hit point for it — even asking `isHittable` fails the test.
// Decide from the reported frame instead and drag the row until the tile is fully on screen.
func revealTile(_ tile: XCUIElement, in app: XCUIApplication) {
    guard tile.exists else { return }
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
func openReader(from tile: XCUIElement, in app: XCUIApplication, timeout: TimeInterval = 120) -> XCUIElement {
    let read = app.buttons["Read"].firstMatch
    // Re-reveal the tile before EVERY tap, not just once up front. Two things make a single reveal
    // unreliable: a tap that lands while the LazyRow is still settling is swallowed as a scroll
    // stop, and after re-entering the library (e.g. reopening a book) the target tile is often
    // scrolled past the right edge — so a retry that taps the original position hits an offscreen
    // spot and never opens the detail screen (the reopen-flake root cause). Stop as soon as the
    // detail screen appears, or once the tap has navigated the tile out of the library.
    for attempt in 0..<3 {
        if read.exists { break }
        revealTile(tile, in: app)
        guard tile.exists else { break }
        waitForStableFrame(of: tile)
        tile.tap()
        if read.waitForExistence(timeout: attempt == 0 ? 5 : 30) { break }
    }
    // A final settle wait covers the case where the last tap navigated but the loaded runner is
    // still rendering the item detail.
    XCTAssertTrue(read.waitForExistence(timeout: 30), "Item detail must show the Read action")
    read.tap()
    XCTAssertTrue(read.waitForNonExistence(timeout: timeout), "Read must leave the item detail screen")
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

// True once any library-home section header is on screen.
func waitForLibraryHome(in app: XCUIApplication, timeout: TimeInterval = 120) -> Bool {
    let sectionLabels = ["In Progress", "Recently Added", "Finished", "Continue Series", "All Books", "Series", "Collections"]
    let anySection = NSPredicate { _, _ in sectionLabels.contains { app.staticTexts[$0].exists } }
    let result = XCTWaiter.wait(
        for: [XCTNSPredicateExpectation(predicate: anySection, object: nil)],
        timeout: timeout
    )
    return result == .completed
}
