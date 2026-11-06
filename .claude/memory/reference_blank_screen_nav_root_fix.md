---
name: blank-screen-nav-root-fix
description: Intermittent blank white screen = popUpTo(HOME) no-op accumulating duplicate nav roots; fixed with navigateAsRoot (popUpTo(graph.id) + launchSingleTop)
metadata: 
  node_type: memory
  type: reference
  originSessionId: c5f36875-957b-4f80-a4ce-106228d8639e
---

The "occasional blank white screen, Back exits app" bug: HOME is popped inclusively at launch, so
every later `popUpTo(HOME) { inclusive = true }` matched nothing (`NavController: Ignoring
popBackStack to route home`) — each server/library switch PUSHED a duplicate `library_items` root
instead of replacing it, and backing out could empty the back stack entirely → NavHost with no
destination → blank. Captured live: `backstack=` (empty) in instrumented logging.

Fix: `NavController.navigateAsRoot(route)` in `MainScreen.kt` — `popUpTo(graph.id) { inclusive }` +
`launchSingleTop`, used by all 8 root-switch sites (launch router, server/library switch, redirect,
server-setup completion). Regression test: `NavigateAsRootTest` (androidTest, runs via
`make harness-test-class CLASS=...`). User confirmed fixed on-device.

Disproven along the way: the `redirectToLibrary` collector never fires on normal data (the
Readalouds library lives on the inactive Storyteller server, so it's never in `visibleLibraries`'
input mismatch path); HomeScreen router races were not the trigger.

## RECURRENCE (2026-06-12, branch pkmetski/blank-screen-fix-v2) — NOT yet root-caused

User reports the blank STILL happens on a freshly built current-main APK (drawer footer
`2c452bc7-dirty`), so it is NOT a stale build. The PR #137 fix is merged and genuinely eliminates
the original mechanism — verified: `popUpTo(graph.id)` here means `popUpTo(0)` (root graph id=0);
50+ scripted `navigateAsRoot` calls (library→library is the prime suspect since both share the
SAME route template `library_items/{libraryId}/{libraryName}` with launchSingleTop+popUpTo-graph)
produced ZERO empty `backstack=` and ZERO `Ignoring popBackStack`. Deliberate + fast + stress
reproductions of the user's described gesture all reach `RESUMED` cleanly. Could NOT reproduce with
scripted input on a single-server (ABS only) setup.

User's real repro shape: exit a book/audiobook DETAIL page → tap the burger icon; seen from BOTH
ebook and audiobook libraries. (Earlier handoff also described an unprompted ~1s pop-back, but the
`redirectToLibrary` collector only fires when the active library leaves `visibleLibraries`, which
never happens on a same-server refresh — confirmed `containsLast=true` throughout.)

Staged for the next live capture: instrumented build installed on emulator-5554 with three loggers
(TEMP, marked "remove before merge") — every `navigateAsRoot` call (route+graphId+current+stack),
full back stack on every change, AND per-entry lifecycle transitions via `currentBackStackEntryFlow`
+ `LifecycleEventObserver` (logs `topEntry <route> -> <state>`). The per-entry lifecycle logger is
the key addition: the discriminator is empty stack (collapse) vs a top entry that goes STARTED but
never reaches RESUMED (lifecycle stall — known Navigation-Compose territory, different fix). NOTE:
the back-stack-content logger only re-emits on CONTENT change, so its `[STARTED]` snapshots are
mid-transition and unreliable for lifecycle state — trust the `topEntry` logger for that.
16MB logcat buffer set; dump with `adb -s emulator-5554 logcat -d -s RIFFLE_NAV NavController:I`
right after the user reproduces. Do NOT ship a speculative fix #2 without a captured back stack.

Env gotcha hit this session: sibling workspace left a v33 Room DB on the shared 5554; current-main
(v32) build crashed at launch with "migration from 33 to 32 not found" → `pm clear com.riffle.app`
then re-add ABS (media-server:13378, test/test). See [[reference_shared_emulator_db_downgrade]].

## ROOT CAUSE — CONFIRMED with a DETERMINISTIC repro (2026-06-12)

Repro recipe (100%, 8/8 captured on the unfixed current-main build): on a library list (root) →
open a book/audiobook → **Back** (exit detail, land back on the library root, single entry
`backstack=null[RESUMED], library_items[...]`) → tap the **burger** (drawer starts opening) → press
**Back again while the drawer is mid-open-animation**. Captured trace: `[null, library_items]` →
`backstack=` (EMPTY) ~300–600ms later, with NO `navigateAsRoot` and no input between → blank white
NavHost; Back from there finishes the Activity. A plain fast double-back WITHOUT the burger does NOT
reproduce — the drawer-open is essential.

Mechanism: BackHandlers fire in REVERSE registration order. Priority high→low is
`LibraryItemsScreen's BackHandler` > `NavHost's own OnBackPressedCallback` > `MainScreen's drawer
BackHandler` (the old `BackHandler(enabled = !usePermanentDrawer && drawerState.isOpen)` registers
BEFORE the NavHost, so it can NEVER beat it). During the drawer-open window the library screen's
handler is disabled (`backEnabled = !drawerState.isOpen`) and/or being recomposed away, so Back
falls through to the NavHost callback, which pops the sole root entry → empty. The original PR #137
duplicate-root mechanism is genuinely fixed and unrelated to this.

## CURRENT FIX (2026-06-12, committed 541d1860 on pkmetski/blank-screen-fix-v2)

After FOUR back-handler-level attempts failed (all chasing the trigger; user confirmed the blank
still reproduced on a build with the imperative-OnBackPressedCallback attempt), pivoted to fixing
the INVARIANT: `navigateAsRoot` now does `popUpTo(HOME) { inclusive = false }` instead of
`popUpTo(graph.id) { inclusive = true }`. HOME stays as the permanent base → stack is always
`[home, surface]`, never a sole entry → a Back that reaches the NavHost pops to home (which
re-routes to a library) instead of emptying the NavHost. Because home is never removed,
`popUpTo(HOME)` always matches, so switches still REPLACE the surface (no duplicate-root recurrence
of the #137 bug, which only happened because inclusive=true REMOVED home). NavigateAsRootTest
rewritten to assert home-stays-at-base AND to pop the library ROOT itself (the real blank trigger
the old test never exercised → which is why #137's test passed while the bug shipped). Mentally
traced: old code fails the new test at the first assertion, new code passes. All real flows traced
regression-free (back-to-exit still works via LibraryItemsScreen.Exit→finish which outranks the
NavHost; server switch / setup completion / drawer library-switch all keep home at base).

USER-VERIFIED FIXED (2026-06-12): user (the reliable reproducer) could no longer break it. Known
benign residual: pressing the burger occasionally shows a brief screen DIM that then restores — that
is the race resolving safely now (Back pops to home → home re-routes to the library → the recreated
library_items runs its `LaunchedEffect { drawerState.close() }`, flashing the drawer scrim) instead
of blanking. Cosmetic; could be polished later by also stopping the stray Back from reaching the
NavHost, but NOT worth re-adding the fragile back-handler complexity.

### Superseded attempts (all FAILED — do not retry):
1. always-enabled library_items BackHandler — got disposed during drawer churn, never fired.
2. synchronous drawerOpenRequested flag gating a Compose BackHandler after NavHost — cut empties
   100%→~8% but Compose `BackHandler.enabled` still lags one recompose frame.
3. imperative OnBackPressedCallback toggled synchronously at burger tap, registered after NavHost —
   user STILL reproduced the blank. Back-handler priority/registration is too fragile here.

## (history) FIX ATTEMPT — imperative callback, UNVERIFIED (emulator env broke)

Uncommitted change on pkmetski/blank-screen-fix-v2 (only MainScreen.kt): replaced the drawer-close
`BackHandler` with an imperative `OnBackPressedCallback` (a) registered via DisposableEffect AFTER
the NavHost so it outranks the NavHost callback, and (b) toggled `isEnabled=true` SYNCHRONOUSLY
inside `openDrawer` (burger tap) — eliminating the Compose `BackHandler.enabled` one-frame
recompose lag (a plain synchronous-flag version only cut empties 100%→~8%). NOT CONFIRMED WORKING:
on API-33 testing one tight-timing Combo B still blanked once; could not determine fix-failure vs a
burger-tap-miss artifact because the shared macOS host kept OOM-killing the emulator's system_server
under load (user's 5554 + others + my builds). SUSPECT the fix may be INCOMPLETE: there may be a
second, drawer-independent path (fast double-back during the detail→list transition popping the root
on API 33). The genuinely robust fix is likely a NAVIGATION-LEVEL guard: a single always-enabled
back authority registered after the NavHost that, at the root, closes the drawer if open else
finishes the Activity — NEVER pops the NavHost to empty (predictive back is OFF —
`enableOnBackInvokedCallback` unset — so shadowing the NavHost callback costs no back animation; the
only friction is preserving LibraryItemsScreen's clear-search/reset-tab/exit layered back from #60,
which needs delegating that screen's state up). Verify on a STABLE device before claiming fixed.

Own throwaway AVD made this session: `Santiago_Repro` (android-33 google_apis arm64, medium_phone),
booted on port 5600 — but it kept dying under host load. Per [[feedback_never_touch_other_emulators]]
NEVER use the user's 5554; create/boot your own and stick to it.
