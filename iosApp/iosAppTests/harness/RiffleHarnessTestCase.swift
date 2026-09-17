import XCTest

// Base class for harness tests that need a pre-configured ABS source. Mirrors the pattern of
// Android's GoldenTraceHarnessTest: starts the stub server, resets app state via launch arg,
// then drives the add-source UI to connect to the stub — leaving each test with the library
// home already on screen.
class AbsHarnessTestCase: XCTestCase {

    var app: XCUIApplication!
    var absServer: StubAbsServer!

    override func setUpWithError() throws {
        continueAfterFailure = false
        absServer = StubAbsServer()
        absServer.start()

        app = XCUIApplication()
        app.launchArguments += ["--RIFFLE_RESET_FOR_TESTS"]
        app.launch()

        try connectAbsSource(to: absServer.baseUrl)
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
        absServer.shutdown()
        absServer = nil
    }

    // Drives the add-source UI flow to connect to the stub ABS server.
    private func connectAbsSource(to url: String) throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 40),
                      "App must start with the source picker (reset hook must have fired)")
        let absCard = app.staticTexts["Audiobookshelf"]
        XCTAssertTrue(absCard.waitForExistence(timeout: 5), "ABS card must be in the picker")
        absCard.tap()

        // Wait for the credential form
        let schemeButton = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH 'https://'"))
            .firstMatch
        XCTAssertTrue(schemeButton.waitForExistence(timeout: 10), "Scheme selector must appear")

        // Type the full URL with "http://"; the ViewModel's updateHost() auto-detects the
        // scheme — avoids tapping the DropdownMenu which crashes the test runner.
        fillField(in: app, labeled: "Source URL", with: url)
        fillField(in: app, labeled: "Username", with: "testuser")
        fillField(in: app, labeled: "Password", with: "test")

        let connect = revealConnectButton(in: app)
        XCTAssertTrue(connect.exists, "Connect button must be reachable once fields are filled")
        XCTAssertTrue(connect.isEnabled, "Connect must be enabled once fields are filled")
        connect.tap()

        if app.buttons["Connect anyway"].waitForExistence(timeout: 10) {
            app.buttons["Connect anyway"].tap()
        }

        XCTAssertTrue(app.staticTexts["Select libraries"].waitForExistence(timeout: 30),
                      "Successful login must land on select-libraries step")
        let continueButton = app.buttons["Continue"]
        XCTAssertTrue(continueButton.waitForExistence(timeout: 5))
        if !continueButton.isEnabled {
            let firstSwitch = app.switches.firstMatch
            XCTAssertTrue(firstSwitch.waitForExistence(timeout: 5))
            firstSwitch.tap()
        }
        continueButton.tap()

        XCTAssertTrue(app.buttons["Open menu"].waitForExistence(timeout: 60),
                      "Library home must render after adding ABS source")
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
        app.launchArguments += ["--RIFFLE_RESET_FOR_TESTS"]
        app.launch()

        try connectKomgaSource(to: komgaServer.baseUrl)
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
        komgaServer.shutdown()
        komgaServer = nil
    }

    private func connectKomgaSource(to url: String) throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 40),
                      "App must start with the source picker")
        let komgaCard = app.staticTexts["Komga"]
        XCTAssertTrue(komgaCard.waitForExistence(timeout: 5), "Komga card must be in the picker")
        komgaCard.tap()

        // Wait for the credential form
        let schemeButton = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH 'https://'"))
            .firstMatch
        XCTAssertTrue(schemeButton.waitForExistence(timeout: 10), "Scheme selector must appear")

        // Type the full URL with "http://"; the ViewModel's updateHost() auto-detects the
        // scheme — avoids tapping the DropdownMenu which crashes the test runner.
        fillField(in: app, labeled: "Source URL", with: url)
        fillField(in: app, labeled: "Username", with: "test@test.test")
        fillField(in: app, labeled: "Password", with: "test")

        let connect = revealConnectButton(in: app)
        XCTAssertTrue(connect.exists, "Connect button must be reachable once fields are filled")
        XCTAssertTrue(connect.isEnabled)
        connect.tap()

        if app.buttons["Connect anyway"].waitForExistence(timeout: 10) {
            app.buttons["Connect anyway"].tap()
        }

        XCTAssertTrue(app.staticTexts["Select libraries"].waitForExistence(timeout: 30),
                      "Successful login must land on select-libraries step")
        let continueButton = app.buttons["Continue"]
        XCTAssertTrue(continueButton.waitForExistence(timeout: 5))
        if !continueButton.isEnabled {
            let firstSwitch = app.switches.firstMatch
            XCTAssertTrue(firstSwitch.waitForExistence(timeout: 5))
            firstSwitch.tap()
        }
        continueButton.tap()

        XCTAssertTrue(app.buttons["Open menu"].waitForExistence(timeout: 60),
                      "Library home must render after adding Komga source")
    }

}

// Shared helper used by both harness base classes and standalone test classes.
func fillField(in app: XCUIApplication, labeled label: String, with text: String) {
    let fieldLabel = app.staticTexts[label]
    XCTAssertTrue(fieldLabel.waitForExistence(timeout: 5), "\(label) field must exist")
    fieldLabel.tap()
    let focused = app.textFields.firstMatch.exists
        ? app.textFields.firstMatch
        : app.secureTextFields.firstMatch
    if focused.exists {
        focused.typeText(text)
    } else {
        app.typeText(text)
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
