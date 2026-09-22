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

    // MARK: - Zero-config unbounded catalogues: install and land on a browsable library

    /// Installs a zero-config catalogue and asserts the user lands on a library they can browse.
    ///
    /// #1071 §17: these installs always "worked" — the source and library rows were written and
    /// `IosLibraryRefresherImpl` returned Success — but iOS had no `CatalogFactory` for them and
    /// no source-type fork in its library host, so `LibraryItemsScreen` rendered a permanently
    /// empty library with no error. Landing on library home was therefore not enough to prove
    /// anything, which is why these tests now assert the *browse surface* is what rendered.
    ///
    /// The chip-strip assertion is the deterministic half: "Not Started" and "All" are rendered
    /// by `UnboundedBrowseLibraryTab` and by nothing else on iOS, so they go red the moment the
    /// host routes an unbounded catalogue back to `LibraryItemsScreen`.
    ///
    /// The second half is network-independent by construction. With no `CatalogFactory`
    /// registered, `UnboundedBrowseViewModel.refreshOnce` returns at its
    /// `activeCatalog() ?: return` and the grid sits on "No items in this library" forever, with
    /// no error. With one registered, the grid settles on either catalogue items (network up) or
    /// the source's friendly error (network down) — never the silent empty state. So "the empty
    /// state is gone once the fetch settles" is exactly the defect, and holds on a CI runner
    /// whose egress to chitanka.info / gutendex.com / radio-api.net is blocked.
    private func assertInstallsAndBrowses(
        card cardTitle: String,
        confirmTitle: String,
        rowIdentifier: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertTrue(app.staticTexts["Add source"].waitForExistence(timeout: 120),
                      "App must start on the source picker", file: file, line: line)

        let card = revealCard(cardTitle)
        XCTAssertTrue(card.exists, "\(cardTitle) card must be visible", file: file, line: line)

        let hittable = NSPredicate(format: "hittable == true")
        wait(for: [XCTNSPredicateExpectation(predicate: hittable, object: card)], timeout: 10)
        card.tap()

        // B3: a confirmation screen must appear — not an immediate install.
        XCTAssertTrue(
            app.staticTexts[confirmTitle].waitForExistence(timeout: 10),
            "Tapping the \(cardTitle) card must show the confirmation screen, not install immediately",
            file: file, line: line
        )
        let addButton = app.buttons["Add source"]
        XCTAssertTrue(addButton.waitForExistence(timeout: 5),
                      "Confirmation screen must have an Add source button", file: file, line: line)
        addButton.tap()

        let burger = app.buttons["Open menu"]
        XCTAssertTrue(
            burger.waitForExistence(timeout: 60),
            "\(cardTitle) install must land on the library home",
            file: file, line: line
        )
        XCTAssertTrue(app.state == .runningForeground,
                      "App must survive the \(cardTitle) install", file: file, line: line)

        assertBrowseSurfaceIsShowing(for: cardTitle, file: file, line: line)
        assertSourceCanBeRemoved(rowIdentifier: rowIdentifier, burger: burger, file: file, line: line)
    }

    /// The removal tail both install tests carried before #1071 §17 gated them out.
    ///
    /// The behavioural claim is unchanged — Settings lists the installed source, and the user can
    /// remove it from there — but the affordance is not. iOS used to render its own "Remove" text
    /// button (`settings-trailing-Remove`) beside a flat source row, while Android removed a
    /// source with an end-to-start swipe. Both hosts now render `feature:source-ui`'s shared
    /// `SourcesSection`, so iOS has Android's swipe and no longer has the iOS-only text button:
    /// the gesture this drives is the one Android's `SwipeToDeleteRowTest` covers.
    ///
    /// The final assertion is now "the row is gone" rather than the old "No sources configured"
    /// copy, which the shared section (like Android's) does not render.
    private func assertSourceCanBeRemoved(
        rowIdentifier: String,
        burger: XCUIElement,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        burger.tap()
        let settingsEntry = app.staticTexts["Settings"]
        XCTAssertTrue(settingsEntry.waitForExistence(timeout: 10),
                      "Drawer must offer Settings", file: file, line: line)
        settingsEntry.tap()
        let sourceRow = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == %@", rowIdentifier)).firstMatch
        XCTAssertTrue(
            sourceRow.waitForExistence(timeout: 15),
            "Settings must list the installed source (\(rowIdentifier))",
            file: file, line: line
        )

        // A full end-to-start swipe, not a flick: SwipeToDismissBox only commits past its
        // positional threshold, and a short XCUITest swipeLeft() lands short of it.
        let start = sourceRow.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5))
        let end = sourceRow.coordinate(withNormalizedOffset: CGVector(dx: -0.6, dy: 0.5))
        start.press(forDuration: 0.1, thenDragTo: end)

        XCTAssertTrue(
            sourceRow.waitForNonExistence(timeout: 15),
            "Swiping the source row away must remove the source",
            file: file, line: line
        )
    }

    private func assertBrowseSurfaceIsShowing(
        for cardTitle: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let chipStrip = NSPredicate { _, _ in
            self.app.staticTexts["Not Started"].exists || self.app.staticTexts["All"].exists
        }
        // On a loaded CI runner the Compose layout can take >60 s to settle after a cold-start
        // install; 90 s provides headroom while remaining well under the overall job limit.
        XCTAssertEqual(
            XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: chipStrip, object: nil)], timeout: 90),
            .completed,
            "\(cardTitle) must open the unbounded browse surface, not the Room-backed library screen",
            file: file, line: line
        )

        let settled = NSPredicate { _, _ in
            !self.app.staticTexts["No items in this library"].exists
        }
        XCTAssertEqual(
            XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: settled, object: nil)], timeout: 90),
            .completed,
            "\(cardTitle)'s catalogue must resolve to items or a real error — a permanently empty "
                + "grid with no error is the #1071 §17 defect (no CatalogFactory registered)",
            file: file, line: line
        )
    }

    // MARK: - 17.3  Project Gutenberg install (zero-config, no server)

    func testGutenbergInstallDoesNotCrash() throws {
        assertInstallsAndBrowses(
            card: "Project Gutenberg",
            confirmTitle: "Add Project Gutenberg",
            rowIdentifier: "GUTENBERGSourceRow"
        )
    }

    // MARK: - 17.6  radio.es install (zero-config, no server)

    func testRadioEsInstallDoesNotCrash() throws {
        assertInstallsAndBrowses(
            card: "radio.es",
            confirmTitle: "Add radio.es",
            rowIdentifier: "RADIO_ESSourceRow"
        )
    }
}
