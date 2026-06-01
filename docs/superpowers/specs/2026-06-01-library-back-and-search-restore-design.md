# Library back button & search restore (issue #60)

## Problem

Two related defects on the library screen (`LibraryItemsScreen`):

1. **Back is unpredictable.** Reaching `library_items` calls
   `drawerState.close()` from a coroutine *after* navigating, so when the
   user pressed the burger and then tapped a library, the drawer can still
   be in its `Open` state when the user presses Back. The modal drawer's
   built-in `BackHandler` then captures the press and closes the drawer
   instead of doing what the user expects. There is no explicit
   `BackHandler` on the library screen, so once the drawer is closed Back
   exits the app — but the timing makes the behavior feel random.
2. **Search query is wiped on book-detail round-trip.** Tapping a search
   result navigates to the detail screen; on the way back, the library
   screen's `ON_RESUME` observer unconditionally calls
   `viewModel.onSearchQueryChange("")` (`LibraryItemsScreen.kt:134`),
   discarding the query and the search-results UI.

## Decision

### Part A — Layered Back on the library screen

Replace the implicit, drawer-dependent Back behavior with an explicit
`BackHandler` inside `LibraryItemsScreen` that applies, in order:

1. If `searchQuery.isNotEmpty()` → clear the query, clear focus, hide the
   keyboard. Stay on the library screen.
2. Else if `selectedTab != 0` (Home) and the library is not a Readaloud
   library → set `selectedTab = 0`. Stay on the library screen.
3. Else → finish the activity (exit the app).

Also, in the `LIBRARY_ITEMS` destination block in `MainScreen.kt`, add a
`LaunchedEffect(Unit) { drawerState.close() }` so reaching the library
fully closes the drawer synchronously with the destination's first
composition. This eliminates the stale-`Open` window that currently
causes the unpredictable Back behavior even when the user reaches
library via a drawer tap.

### Part B — Search query survives the round-trip

- In `LibraryItemsViewModel`, replace the plain `MutableStateFlow("")`
  for `searchQuery` with one backed by `SavedStateHandle.getStateFlow`
  under key `"searchQuery"`. `onSearchQueryChange` writes through to
  `savedStateHandle["searchQuery"] = query`. This preserves the query
  across process death too.
- In `LibraryItemsScreen`, delete the
  `viewModel.onSearchQueryChange("")` line in the `ON_RESUME` observer.
  Keep `focusManager.clearFocus(force = true)` and
  `keyboardController?.hide()` — we don't want focus or the keyboard to
  return automatically on resume, only the query.

## Testing

Add four harness tests under
`app/src/androidTest/kotlin/com/riffle/app/feature/library/`. Each
launches `MainActivity`, navigates into a seeded library, and asserts on
Compose semantics.

1. `LibraryBackClearsSearchTest` — type a query into the search field,
   press Back, assert the query is empty and the search-results UI is
   gone, and the activity is *not* finishing.
2. `LibraryBackResetsTabTest` — select tab 4 (All Books), press Back,
   assert tab 0 (Home) is selected and the activity is not finishing.
3. `LibraryBackExitsAppTest` — with tab 0 selected and no search,
   press Back, assert the activity *is* finishing.
4. `LibrarySearchRestoredOnBackFromDetailTest` — type a query, tap a
   result, press Back from detail, assert the search query is still
   present in the library's search field and the search-results UI is
   showing.

## Out of scope

- "Press Back again to exit" confirmation. Not adopted — the layered
  Back already removes most accidental exits.
- Restoring scroll position inside search results across the
  round-trip. The current scroll position is held by `LazyColumn`'s own
  saver; if it doesn't survive, that is a separate ticket.
- Persisting `selectedTab` across process death. Already handled by
  `rememberSaveable` and not affected by this change.
