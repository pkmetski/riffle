# ADR 0007 — Navigation Drawer Replaces ServerListScreen and LibraryListScreen

The original navigation stack required the user to pass through two full screens (ServerList → LibraryList) before reaching books, and offered no way to switch libraries without going back two levels. We replaced this with a persistent Navigation Drawer: the drawer header exposes a Server Switcher dropdown, and the drawer body lists the visible Libraries for the active Server. LibraryItemsScreen becomes the effective home. ServerListScreen is retired; its switch behaviour moves into the drawer and its add/delete behaviour moves into Settings.

## Considered Options

- **Keep LibraryListScreen, add a shortcut:** Rejected — it still exists as a mandatory step and doesn't solve the lateral-switching problem.
- **Bottom navigation tabs (one tab per library):** Rejected — tab bars don't scale beyond 4–5 items and libraries are user-defined in quantity.

## Consequences

- `LibraryListScreen` and `ServerListScreen` are removed from the NavGraph.
- Settings absorbs server management (Add Server, swipe-to-delete) and Library Visibility Preferences (per-server hide toggles, at least one library must remain visible).
- On launch with no configured Server the app opens to `AddServerScreen`. After a Server is added, or when switching Servers, the app navigates to the first non-hidden Library in the server-defined order.
- Library order is server-defined; the app provides no reordering — the user reorders on the ABS server side.
- The "Unsupported Library" concept (auto-dimmed, non-tappable library entries) is retired. Libraries are shown equally in the drawer; the user decides which to hide via Library Visibility Preferences.
