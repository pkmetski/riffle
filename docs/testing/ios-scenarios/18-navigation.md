# 18 — Navigation (iOS)

## 18.1 Drawer gesture disabled in reader to avoid intercepting EPUB page-turn swipes

**Given** the EPUB reader is open (gesturesEnabled = false on the drawer).
**When** the user performs a horizontal swipe gesture inside the reader.
**Then** the navigation drawer does NOT open — the swipe is passed through to the EPUB navigator.

**Coverage:** `NavigationDrawerGestureTest.drawerDoesNotOpenViaGestureWhenGesturesDisabled`

**iOS gap:** UIKit gesture recogniser priority is the iOS mechanism; the ReadiumEpubNavigatorBridge already owns horizontal swipes. Verified manually: open EPUB, swipe left/right — reader pages turn, drawer does not open.

## 18.2 Drawer gesture enabled on library screen

**Given** the library screen is visible (gesturesEnabled = true).
**When** the user swipes from the left edge.
**Then** the navigation drawer opens via gesture.

**Coverage:** `NavigationDrawerGestureTest`

**iOS gap:** Gesture-enabled state toggling is Compose-only. On iOS the side panel is always gesture-openable from the library screen; verified manually.

## 18.3 Permanent drawer renders without requiring a tap (tablet)

**Given** the device is in Expanded (tablet) width class.
**When** the library screen loads.
**Then** the drawer panel is permanently visible alongside the content — no hamburger button, no modal scrim.

**Coverage:** `PermanentNavigationDrawerTest.permanentDrawerRendersContentsWithoutOpening`

**iOS gap:** SwiftUI `NavigationSplitView` provides the equivalent persistent sidebar on iPad. Verified manually via Xcode build on iPad.

## 18.4 Back-stack navigation keeps HOME as permanent base

**Given** the user navigates from home → library → settings.
**When** the user navigates back from settings.
**Then** they land on library, not a blank screen; the back stack is never empty below the active destination.

**Coverage:** `NavigateAsRootTest.settingsBackDoubleTapOnlyPopsSettings`

**iOS gap:** SwiftUI `NavigationStack` manages back-stack differently; the blank-screen root cause (empty NavHost) does not apply. iOS navigation uses path arrays; the home view is always the root. This is a structural guarantee of the platform, not a runtime behaviour — there is no
meaningful runtime assertion to make. The former `NavigationTests.swift` file (whose only test
re-asserted the `ServerType.storytellerService` enum constant, duplicating
`DrawerViewModelTest` in commonTest) was removed.

## 18.5 Root-switching navigation does not stack duplicate home entries

**Given** the user switches between library destinations multiple times via the drawer.
**Then** each switch replaces the active destination; no duplicate library entries accumulate in the back stack.

**Coverage:** `NavigateAsRootTest`

**iOS gap:** iOS uses path-based `NavigationStack`; duplicate stacking is not possible. Documented for completeness.
