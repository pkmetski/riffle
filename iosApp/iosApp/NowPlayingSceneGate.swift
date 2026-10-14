import SwiftUI

/// Returns true when the app is transitioning from `.background` to `.active` — the only
/// transition that represents a deliberate "open the player from the lock screen or a
/// media-notification tap". Other transitions (`.inactive` → `.active`) happen during Control
/// Centre pulls, incoming phone calls, and notification banners and must not trigger a nav push.
func shouldRequestNowPlayingOpen(from previous: ScenePhase, to current: ScenePhase) -> Bool {
    current == .active && previous == .background
}
