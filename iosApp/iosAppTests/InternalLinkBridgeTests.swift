import XCTest
import Riffle

// Unit tests for the internal-link-callback wiring in ReadiumEpubNavigatorBridge.
//
// The delegate path (navigator(_:shouldFollowInternalLink:referrer:)) cannot be driven
// directly in a unit test because it requires a live Readium Navigator instance. The bridge
// exposes simulateInternalLinkTap(href:originLocatorJson:) as a test seam that exercises
// the same dispatch logic. Reverting setInternalLinkCallback's storage or removing the
// guard would each flip at least one test here red.
final class InternalLinkBridgeTests: XCTestCase {

    func testCallbackReceivesHrefAndOrigin() {
        let bridge = ReadiumEpubNavigatorBridge()
        var receivedHref: String?
        var receivedOrigin: String?
        bridge.setInternalLinkCallback { href, origin in
            receivedHref = href
            receivedOrigin = origin
        }

        _ = bridge.simulateInternalLinkTap(href: "chapter2.html#section1",
                                           originLocatorJson: "{\"href\":\"chapter1.html\"}")

        XCTAssertEqual(receivedHref, "chapter2.html#section1")
        XCTAssertEqual(receivedOrigin, "{\"href\":\"chapter1.html\"}",
                       "Callback must receive exact href and origin locator JSON")
    }

    func testAlwaysReturnsTrue() {
        let bridge = ReadiumEpubNavigatorBridge()
        bridge.setInternalLinkCallback { _, _ in }

        let result = bridge.simulateInternalLinkTap(href: "chapter.html",
                                                    originLocatorJson: "{}")

        XCTAssertTrue(result,
                      "shouldFollowInternalLink must return true so Readium navigates to the target")
    }

    func testReturnsTrueWhenNoCallbackRegistered() {
        let bridge = ReadiumEpubNavigatorBridge()

        let result = bridge.simulateInternalLinkTap(href: "section.html", originLocatorJson: "{}")

        XCTAssertTrue(result,
                      "Must return true even when no callback is registered")
    }

    func testCallbackNotInvokedAfterCleared() {
        let bridge = ReadiumEpubNavigatorBridge()
        var callCount = 0
        bridge.setInternalLinkCallback { _, _ in callCount += 1 }
        bridge.setInternalLinkCallback(callback: nil)

        _ = bridge.simulateInternalLinkTap(href: "chapter.html", originLocatorJson: "{}")

        XCTAssertEqual(callCount, 0,
                       "Cleared callback must not fire on subsequent internal-link taps")
    }
}
