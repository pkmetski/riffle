---
name: compose-ios-ime-inset-clips-nonscrollable-forms
description: iOS root Surface uses safeDrawingPadding (includes IME inset) — a fillMaxSize non-scrollable Column gets squeezed when the keyboard opens and buttons below the focused field are clipped AND dropped from the XCUITest accessibility tree
metadata: 
  node_type: memory
  type: reference
  originSessionId: 7cf17588-a5d2-4084-9231-bfe028665ad4
  modified: 2026-09-17T05:39:15.391Z
---

Found 2026-09-17 debugging `app.buttons["Connect"]` not found on the add-source form (all ABS/Komga harness
tests failed in setUp at ~23 s). `MainViewController.kt` wraps content in `Surface(Modifier.fillMaxSize().safeDrawingPadding())`;
safeDrawing includes `WindowInsets.ime`, so the whole composition shrinks by keyboard height (plus the
"Passwords" autofill bar). A `Column(fillMaxSize)` form then overflows and the Connect button is clipped to a
thin strip; Compose iOS a11y omits clipped nodes, so XCUITest sees a Button with no label.

**How to apply:** forms with a submit button below text fields need `verticalScroll(rememberScrollState())`
(done for `AddSourceScreen`). In XCUITests, dismiss the keyboard before querying elements below the focused
field — tapping a non-focusable area (`app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).tap()`)
clears Compose focus; see `revealConnectButton`/`dismissKeyboard` in `iosAppTests/harness/RiffleHarnessTestCase.swift`.
Diagnose with the xcresult: `xcrun xcresulttool export attachments` gives the a11y hierarchy .txt + screen recording
(`ffmpeg -sseof -N` for frames). Related: [[ios-unsigned-sim-keychain-fails]].
