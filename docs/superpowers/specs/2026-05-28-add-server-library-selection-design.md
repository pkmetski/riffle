# Add-Server Library Selection — Design

## Goal

When adding a server, force the user to explicitly choose which libraries are
active in Riffle before the server is committed. Today every library on a newly
added server is visible by default; the user only discovers per-library
toggles later in Settings. This makes the choice front-and-center and atomic
with the server add.

## User flow

1. User opens AddServerScreen, enters URL + credentials, taps **Connect**.
2. Repository authenticates and fetches libraries in one step. Nothing is
   persisted yet.
3. On success, app navigates to a new **SelectLibrariesScreen**.
4. The screen lists the server's book libraries with toggles, all **ON** by
   default. **Continue** is disabled when zero are selected.
5. Tap **Continue** → server, token, library cache, and per-server hidden-id
   set are written together. Setup graph is popped; user lands on Home.
6. Back from SelectLibrariesScreen → nothing to roll back (nothing persisted).
   AddServerScreen state is preserved because its ViewModel lives in the
   nested graph.
7. Errors during authenticate (network, 401, cert, library fetch) → stay on
   AddServerScreen with the existing error UI.

## Architecture

### Repository changes

`ServerRepository.addServer(...)` is split into two operations:

- `authenticate(url, username, password, allowInsecure): Result<PendingServer>`
  - Validates the URL.
  - Performs the existing login call.
  - Calls `AbsLibraryApi.getLibraries()` filtered to book libraries.
  - Returns an in-memory `PendingServer` holder containing: server URL, display
    name, auth token, user id, and `List<Library>`.
  - Persists nothing.
- `commit(pending: PendingServer, hiddenLibraryIds: Set<String>): Result<ServerId>`
  - Inserts the server row (marked active if it's the first).
  - Writes the token to `TokenStorage`.
  - Caches the library rows via `LibraryDao` (same shape as today's lazy
    refresh).
  - Writes the hidden set via `LibraryVisibilityPreferencesStore` keyed on the
    new server id.
  - All writes performed inside a single coroutine; on failure the previously
    written rows are rolled back via `withTransaction` where possible, and the
    token is cleared on any non-Room failure.

`PendingServer` is a plain Kotlin data class in the data layer. It contains a
raw token, so it must never be passed through nav route arguments.

### Navigation

A new nested nav graph **`server_setup`** wraps the existing `add_server`
route and the new `select_libraries` route:

```
server_setup (graph)
├── add_server (start destination)
└── select_libraries
```

A `ServerSetupViewModel`, scoped to the `server_setup` graph entry, holds the
`PendingServer` so both screens can read it without nav arguments. The
existing `AddServerViewModel` becomes scoped to `add_server` within the graph;
its form state survives a back-pop from `select_libraries`.

On successful commit the app calls
`popBackStack(home, inclusive = false)` (or the equivalent for the launching
route), clearing the whole `server_setup` graph in one shot.

### New screen: SelectLibrariesScreen

- `TopAppBar`: title "Select libraries", back arrow (default nav-up).
- Subtitle: "Choose which libraries to show in Riffle. You can change this
  later in Settings."
- A `LazyColumn` of `ListItem` rows, one per library:
  - Library name as the title.
  - Trailing `Switch` bound to per-library selection state.
- Sticky bottom **Continue** button.
  - Disabled when no libraries are selected.
  - Helper text below when disabled: "Select at least one library".
- Empty state (server returned zero book libraries): a message plus a
  **Go back** button — no Continue button.
- No loading state needed: libraries are already in `PendingServer`.

### SelectLibrariesViewModel

- Reads `PendingServer` from the shared `ServerSetupViewModel`.
- Holds `selectedIds: Set<String>` in saved state, initialized to all library
  ids.
- Exposes `toggle(libraryId)`, `continueClicked()`, and a UI state with
  `libraries`, `selectedIds`, `isSubmitting`, and `errorMessage`.
- On `continueClicked`:
  - Compute `hiddenIds = allIds - selectedIds`.
  - Call `serverRepository.commit(pending, hiddenIds)`.
  - On success: emit a one-shot navigation event consumed by the screen.
  - On failure: surface a snackbar with **Retry**; pending state is preserved.

## Error handling matrix

| Failure | Behavior |
|---|---|
| Network / 401 / cert error during `authenticate` | Stay on AddServerScreen, existing error UI. Nothing persisted. |
| Library fetch fails inside `authenticate` | Treated as authenticate failure. Error message: "Connected, but couldn't load libraries — try again." |
| Back from SelectLibrariesScreen | `popBackStack`. No rollback needed. AddServerScreen fields preserved. |
| `commit` fails (DB or token write) | Snackbar on SelectLibrariesScreen with Retry. Partial writes rolled back inside `commit`. Pending state preserved. |
| Server returns zero book libraries | Empty-state UI. Only option is **Go back** — no commit possible. |

## Testing

- `ServerRepositoryImpl`:
  - `authenticate` returns a `PendingServer` with libraries and writes nothing
    to DB or token storage.
  - `commit` writes server + token + library cache + hidden set together.
  - A simulated commit failure leaves no partial state.
- `SelectLibrariesViewModel`:
  - Initial state has all libraries selected.
  - Toggling a library updates selection.
  - Continue is disabled when selection is empty.
  - Successful commit emits the navigate-home event.
  - Failed commit surfaces an error and keeps pending state.
- `AddServerViewModel`:
  - On success, emits a `NavigateToSelectLibraries` event (replacing the
    previous pop-back event).
- Existing add-server integration test (if any) is updated to walk through the
  new screen.

## Out of scope

- Retroactive prompt for already-added servers — they keep current behavior
  (all visible until the user toggles in Settings).
- Reordering, renaming, or hiding non-book libraries beyond what ABS already
  exposes.
- A "select none" affordance — at least one library must remain selected.
