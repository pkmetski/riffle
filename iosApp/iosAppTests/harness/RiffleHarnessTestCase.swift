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
    if !burger.waitForExistence(timeout: 60) {
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
// (or audiobook player). Returns the reader's "← Back" control. Every reader and the player
// render "← Back" as a clickable text, which XCUITest exposes as a Button; the item detail
// screen shows the same control, so the Read button vanishing is what proves the reader opened.
@discardableResult
func openReader(from tile: XCUIElement, in app: XCUIApplication, timeout: TimeInterval = 30) -> XCUIElement {
    revealTile(tile, in: app)
    let read = app.buttons["Read"].firstMatch
    // A tap that lands while the LazyRow is still settling after the reveal drag is consumed as a
    // scroll stop rather than a click, so give the row a moment and retry once if nothing opened.
    for attempt in 0..<2 {
        waitForStableFrame(of: tile)
        tile.tap()
        if read.waitForExistence(timeout: attempt == 0 ? 5 : 15) { break }
    }
    XCTAssertTrue(read.exists, "Item detail must show the Read action")
    read.tap()
    XCTAssertTrue(read.waitForNonExistence(timeout: timeout), "Read must leave the item detail screen")
    let back = app.buttons["← Back"].firstMatch
    XCTAssertTrue(back.waitForExistence(timeout: timeout), "Reader must show ← Back")
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
func waitForLibraryHome(in app: XCUIApplication, timeout: TimeInterval = 10) -> Bool {
    let sectionLabels = ["In Progress", "Recently Added", "Finished", "Continue Series", "All Books", "Series", "Collections"]
    let anySection = NSPredicate { _, _ in sectionLabels.contains { app.staticTexts[$0].exists } }
    let result = XCTWaiter.wait(
        for: [XCTNSPredicateExpectation(predicate: anySection, object: nil)],
        timeout: timeout
    )
    return result == .completed
}
