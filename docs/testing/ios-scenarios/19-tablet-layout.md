# 19 — Tablet Layout Container (iOS)

## 19.1 Content is capped at 600dp and centred on Expanded width class

**Given** the device is in Expanded (tablet) width class.
**When** a screen wrapped in the tablet content-width container renders.
**Then** the content width is at most 600dp and it is centred horizontally within the screen.

**Coverage:** `TabletContentWidthContainerTest.onExpanded_contentIsCappedAt600dpAndCentred`

**iOS gap:** On iOS/SwiftUI this is implemented with a `.frame(maxWidth: 600)` centred in the window. Verified manually via Xcode build on iPad: Settings screen content stays centred and does not span the full width.

## 19.2 Content is full-width (no cap) on Compact / Medium width class

**Given** the device is in Compact or Medium (phone) width class.
**When** a screen wrapped in the tablet content-width container renders.
**Then** the content fills the full available width — no artificial narrowing.

**Coverage:** `TabletContentWidthContainerNoOpTest`

**iOS gap:** On iPhone the `.frame(maxWidth:)` cap is not applied; full-width rendering is the default. Verified manually: Settings screen on iPhone uses the full screen width.
