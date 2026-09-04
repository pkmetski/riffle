import XCTest
import Riffle

// Covers scenarios from docs/testing/ios-scenarios/2-playlists-to-read.md
//
// The full simulator scenarios (2.1–2.5) require a live ABS instance and must be run manually.
// The tests below pin the invariants that the Kotlin side guarantees: the reserved-name sentinel
// is the right string (so the iOS impl's filter expression is correct), and the playlist name
// used to find the To-Read list matches the sentinel.
final class PlaylistsToReadTests: XCTestCase {

    // Regression: if TO_READ_PLAYLIST_NAME is renamed or its value changes, IosToReadRepositoryImpl
    // will silently look up the wrong playlist name on ABS and the To-Read toggle will stop working.
    func testToReadPlaylistNameSentinel() {
        XCTAssertEqual(ToReadRepositoryKt.TO_READ_PLAYLIST_NAME, "To Read")
    }

    // Regression: both "To Read" and "To Listen" must be in RESERVED_PLAYLIST_NAMES so
    // IosPlaylistsRepositoryImpl's filter hides both from the user-facing Playlists tab.
    func testReservedPlaylistNamesContainsToReadAndToListen() {
        let reserved = PlaylistsRepositoryKt.RESERVED_PLAYLIST_NAMES as! Set<String>
        XCTAssertTrue(reserved.contains("To Read"),
                      "RESERVED_PLAYLIST_NAMES must contain 'To Read' to hide it from the Playlists tab")
        XCTAssertTrue(reserved.contains("To Listen"),
                      "RESERVED_PLAYLIST_NAMES must contain 'To Listen' to hide the ABS audiobook wishlist")
    }
}
