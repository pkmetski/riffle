import Foundation
import ReadiumShared

// MARK: - IosPublicationWarmCache

/// Single-slot in-memory warm cache for the last-opened Readium Publication.
///
/// Keeps the parsed Publication alive after the reader closes so the next cold open of the
/// same book can skip the EPUB asset retrieval + publication parse entirely (the dominant
/// 1-3 s bottleneck on reopen). iOS counterpart of Android's `PublicationWarmCache`.
///
/// All mutations happen on the main actor (openEpub and disposeNavigator are both @MainActor).
/// The class-level annotation is intentionally omitted so the initializer remains nonisolated
/// and test code can construct it synchronously; individual mutating methods carry @MainActor.
final class IosPublicationWarmCache {

    private var slot: (itemId: String, pub: Publication)?

    @MainActor func put(itemId: String, pub: Publication) {
        slot = (itemId, pub)
    }

    @MainActor func take(itemId: String) -> Publication? {
        guard let current = slot, current.itemId == itemId else { return nil }
        slot = nil
        return current.pub
    }

    @MainActor func clear() {
        slot = nil
    }
}
