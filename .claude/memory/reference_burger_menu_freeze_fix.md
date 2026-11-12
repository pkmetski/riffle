---
name: reference-burger-menu-freeze-fix
description: "Burger-menu spinner/hang — all fixes merged in PR #672 (2026-08-06)"
metadata: 
  node_type: memory
  type: reference
  originSessionId: f885d061-de46-4593-98fc-625012d74a59
  modified: 2026-08-06T12:21:24.226Z
---

**PR #672** merged 2026-08-06. Fixed the final bug: double-pop during exit animation.

## Root causes (all fixed)

### 1. Double-pop during exit animation (MainScreen.kt — final fix)
The ← back button on `library_item_detail` sits at the same screen coordinates as ☰ on `library_items`. Compose's exit animation keeps `library_item_detail` alive ~300ms. A second tap during that window re-fires `onNavigateBack()` → second `navController.popBackStack()` removes `library_items` → HOME spinner.
Fix: `guardedNavigateBack(isStillTop, popBack)` — checks `navController.currentBackStackSnapshot().lastOrNull { it.destination.route != null } == backStackEntry` before popping.

### 2. Committed-route vs. preview-route mismatch (MainScreen.kt)
`libraryItemsBackEnabled` was computing against `currentBackStackEntryAsState()` (predictive-back *preview*), disabling the BackHandler during a swipe-back FROM `library_items`.
Fix: `navController.committedTopRouteAsState()` (reads `currentBackStack.collectAsState()`).

### 3. activity.finish() on Android 12+ singleTop (LibraryBackAction.kt)
`finish()` on singleTop re-creates activity and re-delivers Back → infinite loop.
Fix: `moveTaskToBack(true)`.

### 4. navigateFromHome awaitResumed guard (HomeScreen.kt)
HOME briefly RESUMED during `navigateAsRoot`'s `popUpTo(HOME)` step → `navigateFromHome` fires twice.
Fix: `awaitGenuinelyResumedWith` loops until `isStillResumed()` returns true after `waitForResumed()`.

### 5. Server switch spinner (MainScreen.kt)
`onServerSelected` called `navigateAsRoot(HOME)` → spinner while `redirectToLibrary` fires.
Fix: removed `navigateAsRoot(HOME)`; `redirectToLibrary` flow handles navigation.

## Key API notes
- `NavController.currentBackStack` is `@RestrictedApi` — access via `committedTopRouteAsState()` and `currentBackStackSnapshot()` helpers, both annotated `@SuppressLint("RestrictedApi")`.
- `shouldInterceptBackForDrawer`, `libraryItemsBackEnabled`, `committedTopRoute`, `guardedNavigateBack` are all `internal` pure functions with unit tests in `ShouldInterceptBackForDrawerTest.kt`.
