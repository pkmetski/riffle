import XCTest
@testable import shared

class ToReadNonAbsSourceTests: XCTestCase {

    func testAddToReadForNonAbsSource_persistsLocally() async throws {
        let store = IosLocalToReadStore(suiteName: "test.\(UUID().uuidString)")
        await store.add(libraryId: "books", libraryItemId: "book/12345")
        let ids = try await store.observeItemIds(libraryId: "books").first { _ in true }
        XCTAssertTrue(ids?.contains("book/12345") == true)
    }

    func testRemoveFromToReadForNonAbsSource_removesLocally() async throws {
        let store = IosLocalToReadStore(suiteName: "test.\(UUID().uuidString)")
        await store.add(libraryId: "books", libraryItemId: "book/12345")
        await store.remove(libraryId: "books", libraryItemId: "book/12345")
        let ids = try await store.observeItemIds(libraryId: "books").first { _ in true }
        XCTAssertFalse(ids?.contains("book/12345") == true)
    }

    func testIsInToReadForNonAbsSource_reflectsLocalStore() async throws {
        let store = IosLocalToReadStore(suiteName: "test.\(UUID().uuidString)")
        let before = await store.isInToRead(libraryId: "books", libraryItemId: "book/99")
        XCTAssertFalse(before)
        await store.add(libraryId: "books", libraryItemId: "book/99")
        let after = await store.isInToRead(libraryId: "books", libraryItemId: "book/99")
        XCTAssertTrue(after)
    }
}
