# 15 — Readaloud Mini-Player (iOS)

## 15.1 Skip buttons are displayed in the playable state

**Given** the readaloud mini-player is in a playable state (not offline / no bar message).
**Then** rewind, previous chapter, next chapter, and forward skip buttons are all visible.

**Coverage:** `ReadaloudMiniPlayerTest.skipButtons_areDisplayedInThePlayableState`

## 15.2 Skip buttons invoke their respective callbacks

**Given** the mini-player is visible.
**When** each skip button is tapped.
**Then** the corresponding callback (onRewind, onForward, onPreviousChapter, onNextChapter) is invoked exactly once.

**Coverage:** `ReadaloudMiniPlayerTest.skipButtons_invokeTheirCallbacks`

## 15.3 Tapping the speed label opens the speed sheet

**Given** the mini-player shows a speed label (e.g. "1.4×").
**When** the user taps the speed label.
**Then** a speed picker sheet / overlay appears.

**Coverage:** `ReadaloudMiniPlayerTest.tappingTheValue_opensTheSpeedSheet`

## 15.4 Speed preset chip sets the selected speed

**Given** the speed sheet is open.
**When** the user taps a preset speed chip (e.g. "1.25×").
**Then** the speed change callback is invoked with the selected speed value.

**Coverage:** `ReadaloudMiniPlayerTest.presetChip_setsThatSpeed`

## 15.5 Speed label shows granular values

**Given** the current speed is a non-standard value (e.g. 1.4).
**Then** the speed label displays the exact value (e.g. "1.4×"), not a rounded approximation.

**Coverage:** `ReadaloudMiniPlayerTest.speedLabel_showsGranularValues`

## 15.6 Next chapter button disabled at the last chapter

**Given** the player is at the last chapter (canNextChapter = false).
**Then** the next chapter button is disabled / non-interactive.

**Coverage:** `ReadaloudMiniPlayerTest.nextChapter_isDisabledAtTheLastChapter`

## 15.7 Skip buttons hidden when offline (bar message shown instead)

**Given** the player is in offline mode and a "Connect to download" message is being shown.
**Then** the skip buttons (rewind, next chapter) are not visible; the message bar is shown instead.

**Coverage:** `ReadaloudMiniPlayerTest.skipButtons_areAbsentWhileOffline`
