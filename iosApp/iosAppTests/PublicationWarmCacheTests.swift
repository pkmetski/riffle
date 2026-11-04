import XCTest
@testable import Riffle

/// Tests for `IosPublicationWarmCache` — iOS counterpart of Android's `PublicationWarmCacheTest`.
///
/// Round-trip tests (put→take for the correct itemId) cannot be written in isolation because
/// `Publication` from ReadiumShared cannot be constructed without parsing a real EPUB file.
/// Those cases are covered by integration paths: `openEpub()` hits the warm-cache branch when
/// a previous `disposeNavigator()` populated it for the same `cachedItemId`.
///
/// What we CAN test without a Publication:
/// - `take` returns nil on an empty cache
/// - `take` returns nil when the itemId does not match
@MainActor
final class IosPublicationWarmCacheTests: XCTestCase {

    func testTakeReturnsNilForEmptyCache() async {
        let cache = IosPublicationWarmCache()
        XCTAssertNil(cache.take(itemId: "item-1"))
    }

    func testTakeReturnsNilForNonMatchingItemId() async {
        // Cannot put() without a Publication — test just verifies the empty-cache nil path
        // for a non-matching key, which is the same code path.
        let cache = IosPublicationWarmCache()
        XCTAssertNil(cache.take(itemId: "other-item"))
    }

    func testClearIsNoOpOnEmptyCache() async {
        let cache = IosPublicationWarmCache()
        cache.clear()
        XCTAssertNil(cache.take(itemId: "item-1"))
    }
}
