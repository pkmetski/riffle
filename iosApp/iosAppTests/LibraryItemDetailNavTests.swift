import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/3-library-item-detail-nav.md
//
// Full simulator scenarios (3.1–3.7) require a live ABS instance and must be run manually.
// The tests below pin the invariants the Kotlin side guarantees:
//   - The UiState sealed interface compiles with the correct sealed cases (Loading, Ready, Error).
//   - The Ready state carries the expected properties.
// If LibraryItemDetailUiState or its cases are deleted or renamed, these fail immediately.
final class LibraryItemDetailNavTests: XCTestCase {

    // Regression: if the Loading sealed case is removed or renamed the detail screen can never
    // display a spinner while the ViewModel is fetching the item.
    func testLoadingStateExists() {
        let loading: LibraryItemDetailUiState = LibraryItemDetailUiStateLoading.shared
        XCTAssertTrue(loading is LibraryItemDetailUiStateLoading,
                      "Loading should be the LibraryItemDetailUiStateLoading singleton")
    }

    // Regression: if the Error sealed case is removed or renamed the detail screen can never
    // display the "Item not found" fallback (scenario 3.7).
    func testErrorStateExists() {
        let error: LibraryItemDetailUiState = LibraryItemDetailUiStateError.shared
        XCTAssertTrue(error is LibraryItemDetailUiStateError,
                      "Error should be the LibraryItemDetailUiStateError singleton")
    }

    // Regression: if the Ready sealed case is removed or renamed all visible detail content
    // (title, author, cover, buttons) can no longer be rendered (scenarios 3.1–3.6).
    func testReadyStatePropertiesExist() {
        let fakeItem = LibraryItem(
            id: "item1",
            libraryId: "lib1",
            title: "Test Book",
            author: "Test Author",
            coverUrl: nil,
            readingProgress: 0.0,
            isCached: false,
            isDownloaded: false,
            ebookFormat: EbookFormat.epub,
            sourceId: "source1"
        )
        let ready = LibraryItemDetailUiStateReady(
            item: fakeItem,
            authToken: "token",
            isInToRead: false,
            isOffline: false
        )
        XCTAssertEqual(ready.item.id, "item1")
        XCTAssertEqual(ready.item.title, "Test Book")
        XCTAssertFalse(ready.isInToRead)
        XCTAssertFalse(ready.isOffline)
    }
}
