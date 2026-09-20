import XCTest

// Server-free coverage for the Add-Source picker (scenario 17-source-picker).
// Requires a pristine install — guaranteed by --RIFFLE_RESET_FOR_TESTS launch arg; no XCTSkip.
final class SourcePickerTests: XCTestCase {

    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments += ["--RIFFLE_RESET_FOR_TESTS"]
        app.launch()
    }

    override func tearDownWithError() throws {
        app.terminate()
        app = nil
    }

    /// The picker is a `verticalScroll` column of six cards. On a phone the last one sits below
    /// the fold, and Compose/iOS drops off-screen nodes from the accessibility tree entirely — so
    /// a plain `exists` check finds nothing. Scroll it into view the way a user would.
    @discardableResult
    private func revealCard(_ title: String) -> XCUIElement {
        let card = app.staticTexts[title]
        if card.waitForExistence(timeout: 5) { return card }
        for _ in 0..<4 {
            app.swipeUp()
            if card.exists { return card }
        }
        return card
    }

    // MARK: - 17.1  All picker cards visible

    /// The picker must show all six source cards that iOS supports on a pristine install.
    func testAllSourceCardsVisibleOnPristineInstall() throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 40),
                      "App must start on the source picker")
        for title in ["Audiobookshelf", "Local files", "Chitanka", "Project Gutenberg", "Komga", "radio.es"] {
            XCTAssertTrue(revealCard(title).exists, "\(title) card must be visible")
        }
    }

    // MARK: - 17.2  ABS credential form reachable (no server)

    /// Tapping the Audiobookshelf card must open the credential form without needing a live server.
    func testAbsCredentialFormReachable() throws {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 40),
                      "App must start on the source picker")
        let absCard = app.staticTexts["Audiobookshelf"]
        XCTAssertTrue(absCard.waitForExistence(timeout: 5), "Audiobookshelf card must exist")
        absCard.tap()

        let schemeButton = app.buttons
            .matching(NSPredicate(format: "label BEGINSWITH 'https://'"))
            .firstMatch
        XCTAssertTrue(
            schemeButton.waitForExistence(timeout: 10),
            "Credential form must show the scheme selector"
        )
        XCTAssertTrue(app.staticTexts["Source URL"].exists, "Credential form must show a Source URL field")
        XCTAssertTrue(app.staticTexts["Username"].exists, "Credential form must show a Username field")

        let connect = app.buttons["Connect"]
        XCTAssertTrue(connect.waitForExistence(timeout: 5), "Connect button must exist")
        XCTAssertFalse(connect.isEnabled, "Connect must be disabled when the URL field is empty")
    }

    // MARK: - 17.3  Zero-config catalogues iOS cannot browse are offered but not installable

    /// #1071 §17 gated these three out of the picker. Installing them wrote the source and the
    /// library rows and returned Success, but iOS has no browse surface for an unbounded
    /// catalogue — the user landed in a library that was permanently empty, with no error. The
    /// card stays visible (so the feature is discoverable, and #1072 will enable it) but is
    /// disabled and badged, and tapping it must not open the confirmation screen.
    ///
    /// This replaces `testGutenbergInstallDoesNotCrash` / `testRadioEsInstallDoesNotCrash`, which
    /// asserted the install path those gates deliberately removed.
    private func assertNotInstallable(_ title: String, confirmTitle: String) {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 120),
                      "App must start on the source picker")
        let card = revealCard(title)
        XCTAssertTrue(card.exists, "\(title) card must still be visible")

        // The badge is the whole point: a dimmed card that explains nothing is still an inert
        // control. "Coming soon" is rendered inside the card, so scope the query to it.
        XCTAssertTrue(
            app.staticTexts["Coming soon"].exists,
            "\(title) must be badged so the user is told why it cannot be added"
        )

        card.tap()
        XCTAssertFalse(
            app.staticTexts[confirmTitle].waitForExistence(timeout: 5),
            "Tapping \(title) must not reach the confirmation screen — it cannot be browsed on iOS"
        )
        XCTAssertTrue(
            app.staticTexts["Add source"].exists,
            "The picker must still be on screen after tapping a disabled card"
        )
        XCTAssertTrue(app.state == .runningForeground, "Tapping a disabled card must not crash")
    }

    func testGutenbergIsOfferedButNotInstallable() throws {
        assertNotInstallable("Project Gutenberg", confirmTitle: "Add Project Gutenberg")
    }

    func testRadioEsIsOfferedButNotInstallable() throws {
        assertNotInstallable("radio.es", confirmTitle: "Add radio.es")
    }
}
