# Server switcher: identify servers by product, not URL host

## Problem

The navigation drawer header and switcher dropdown render each server using a
display name derived from the URL host (`media-server`, `abs.example.com`). When
a user has both an Audiobookshelf and a Storyteller server on the same machine
(e.g. `media-server:13378` and `media-server:8001`), the two entries are nearly
indistinguishable. Settings → Servers has the same problem.

Neither ABS nor Storyteller exposes a stable "server name" endpoint we can rely
on, so the label must come from the locally-known `ServerType` instead of the
network.

## Decision

Show the **product name** (`"Audiobookshelf"` or `"Storyteller"`) derived from
`ServerType` as the primary identity of every server, everywhere a server is
listed. Drop the URL-host-derived `Server.displayName`.

## UI changes

### Drawer header and switcher (`NavigationDrawerComposable.kt`)

- **Headline:** `"<ServerType.label> [username]"` (e.g. `"Audiobookshelf [plamen]"`).
- **Supporting line:**
  - Single server of this type in the list → version only (or the line is
    omitted if no version is available).
  - More than one server of this type in the list → `"<host> · <version>"`,
    where `host` is the URL authority (`media-server:13378`), not the full URL.
    If no version is available, the line is just `<host>`.
- Storyteller has no `/server-info` endpoint, so no version is ever fetched or
  shown for Storyteller servers. The line collapses gracefully (see above).

### Settings → Servers (`SettingsScreen.kt`)

- **Headline:** `"<ServerType.label>"`.
- **Supporting line:** `"<username> · <url> · <version>"` — the full URL is
  always shown here (per user request: Settings is where you go to verify which
  box you connected to). `version` is omitted if unknown (including all
  Storyteller rows).
- "Active" badge unchanged.

## Code changes

### `core/domain`

- Add `ServerType.label: String` returning `"Audiobookshelf"` / `"Storyteller"`.
  This is the single source of truth for the product name string.
- Remove `Server.displayName: String` from the `Server` data class.

### `core/database`

- Drop the `displayName` column from `ServerEntity` via Room migration
  `MIGRATION_19_20`.
- Bump `RiffleDatabase.version` to 20, register the migration in `DataModule`,
  export the schema JSON (KSP), add a `migration19To20` test in
  `MigrationTest.kt` (validate the column is gone, rows preserved) and extend
  `migrateFullChain`.

### `core/data`

- Remove `displayNameFrom(url)` and all writes/reads of `displayName` in
  `ServerRepositoryImpl`. `Server` is constructed without it.
- Update `ServerRepositoryTest` cases that currently assert
  `displayName == "abs.example.com"` etc. — they should assert on `serverType`
  / `url` instead.
- Update every test stub of `ServerRepository` and every `Server(...)`
  construction across the test suite to drop the `displayName` argument.

### `app/feature/navigation`

- `NavigationDrawerComposable.DrawerHeader` and the switcher `DropdownMenuItem`:
  - Replace `activeServer.displayName` / `server.displayName` with
    `server.serverType.label`.
  - Compute `needsHostDisambiguation = allServers.count { it.serverType == server.serverType } > 1`
    inside the composable and branch the supporting line as described above.
  - Treat `serverType == STORYTELLER` as "no version available" (skip the
    `serverVersions[server.id]` lookup, never render a version segment for it).

### `app/feature/settings`

- `SettingsScreen` server `ListItem`:
  - `headlineContent` → `Text(server.serverType.label)`.
  - `supportingContent` → `username · url · version` as described above; for
    Storyteller servers the `· version` segment is omitted.
- `SettingsScreen` libraries section currently uses `activeServer.displayName`
  for the section header (`"Libraries — $activeServerName"`) — update to
  `serverType.label`.

### `NavigationDrawerViewModel` / `SettingsViewModel`

- Continue fetching versions for ABS servers only (skip Storyteller rows
  entirely — `serverRepository.getServerVersion` already returns `null` for
  them, but we should avoid the call to keep the cache map clean and tests
  honest).

## Tests

Unit / android tests touched:
- `MigrationTest.migration19To20` (new) and `migrateFullChain` extension.
- `ServerRepositoryTest` — drop `displayName` assertions, add a case verifying a
  round-trip preserves `serverType`.
- `NavigationDrawerViewModelTest`, `SettingsViewModelTest`,
  `AddServerViewModelTest`, `HomeViewModelTest`,
  `SeriesDetailViewModelTest`, `LibraryItemsViewModelTest`,
  `LibraryItemDetailViewModelTest`, `CollectionDetailViewModelTest`,
  `ToReadRepositoryTest`, `LibraryRepositoryTest`,
  `EpubRepositoryTest`, `PdfRepositoryTest`,
  `ReadingSessionIntegrationTest`, `ProgressSyncIntegrationTest`,
  `ProgressSyncCycleTest`, `SeriesIntegrationTest`,
  `EpubPositionIntegrationTest` — drop the `displayName` constructor argument
  from every `Server(...)` they build (and from the `getServerVersion` stub
  signature where applicable; signature is unchanged but the in-memory `Server`
  objects need updating).
- `PermanentNavigationDrawerTest`, `NavigationDrawerGestureTest` — update any
  text assertions that look for the host string in the drawer to look for the
  product label instead; verify the version chip appears for ABS and is absent
  for Storyteller.

## Out of scope

- A user-given nickname per server (the `displayName` field could be repurposed
  for this later — for now it is removed entirely; reintroduce if/when needed).
- Fetching a Storyteller version. Storyteller rows never show a version in this
  iteration.
- Localising the product names. Both labels are proper nouns and stay in
  English regardless of locale, matching how they're spelled in the projects
  themselves.

## Prototype

Static HTML mockup at `.context/server-switcher-prototype.html` shows the
final layout for both single-type and multi-type scenarios.
