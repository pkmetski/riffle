import Riffle
import XCTest

/// R-10 regression: publicationOpenErrorCallback must be called on every error branch in
/// openEpub() so the caller can surface a user-visible error rather than silently swallowing it.
final class ReadiumEpubNavigatorBridgeOpenErrorTests: XCTestCase {

    // R-10: An invalid file path must fire publicationOpenErrorCallback, not return silently.
    @MainActor
    func testOpenEpubWithInvalidPathFiresErrorCallback() async {
        let bridge = ReadiumEpubNavigatorBridge()
        let expectation = expectation(description: "open error callback fires")
        var receivedMessage: String?

        bridge.setPublicationOpenErrorCallback { msg in
            receivedMessage = msg
            expectation.fulfill()
        }

        bridge.openEpub(filePath: "/nonexistent/path/book.epub", locatorJson: nil)

        await fulfillment(of: [expectation], timeout: 5)
        XCTAssertNotNil(receivedMessage, "error callback should deliver a non-nil message")
        XCTAssertTrue(
            receivedMessage?.contains("openEpub") == true,
            "error message should identify the method: \(receivedMessage ?? "nil")"
        )
    }
}
