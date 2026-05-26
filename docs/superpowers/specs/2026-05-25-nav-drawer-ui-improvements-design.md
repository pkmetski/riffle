# Navigation Drawer UI Improvements

**Date:** 2026-05-25

## Overview

Three minor UI improvements to the navigation drawer:
1. Show the logged-in username and server version in the server header
2. Pin Downloads + Settings to the bottom of the drawer with the app version below them

## Data Model Changes

### `Server` domain model & `ServerEntity` database entity

Add a `username: String` field to both `Server` (domain) and `ServerEntity` (Room entity).

- Written at login time from `AbsLoginResponse.user.username` — already returned by the API, currently discarded
- Never re-fetched; treated as a stable property of the server account
- Default value `""` for existing rows (migration)

**Database migration:** Bump `RiffleDatabase` version by 1. Migration adds `username TEXT NOT NULL DEFAULT ''` to `server_table`. Schema JSON exported, migration registered in `DataModule`, migration test added per project conventions.

### Server version — in-memory only

Server version is **not** persisted. It is fetched lazily and held in `NavigationDrawerViewModel` as a `Map<serverId, String?>` cache.

- Fetch triggered once when the navigation drawer is opened (if not already cached for the active server)
- API call: `GET /api/server-info` — returns a JSON object with a top-level `version: String` field
- On any network error or unexpected response, the cache entry stays `null` and nothing is shown in the UI
- No loading indicator; the version simply appears once available

## Network Layer

Add `getServerInfo(baseUrl: String, token: String, insecureAllowed: Boolean): String?` to `AbsApi` and implement it in `AbsApiClient`:
- Calls `GET $baseUrl/api/server-info` with Bearer token
- Parses a `AbsServerInfoResponse(val version: String)` DTO
- Returns the version string on success, `null` on any failure

## ViewModel Changes (`NavigationDrawerViewModel`)

- Add `private val serverVersionCache: MutableMap<String, String?>`
- Add `val serverVersion: StateFlow<String?>` — derives from `activeServer` + cache
- Add `fun onDrawerOpened()` — triggers `getServerInfo` for the active server if not yet cached; updates cache and re-emits `serverVersion`

## UI Changes (`NavigationDrawerComposable.kt`)

### `DrawerHeader`

Accept `username: String` and `serverVersion: String?` parameters.

**Headline:** `"My Home Server [plamen]"` — username appended in brackets with `onSurfaceVariant` color, only when username is non-empty.

**Supporting text:** `"http://media-server:13378 · v2.4.1"` — version appended with ` · ` separator, only when `serverVersion` is non-null.

**Dropdown items:** Each server item shows `displayName [username]` (username always available from stored data). URL as before; no version shown for non-active servers.

### `RiffleNavigationDrawer`

Replace the current sequential bottom layout with:

```
Column(Modifier.fillMaxHeight()) {
    DrawerHeader(...)
    // library items
    Spacer(Modifier.weight(1f))
    HorizontalDivider()
    // Downloads NavigationDrawerItem
    // Settings NavigationDrawerItem
    Text("Riffle v${BuildConfig.VERSION_NAME}", small/dimmed/centered)
}
```

`onDrawerOpened` is called from `RiffleNavigationDrawer` when `drawerState.isOpen` becomes true.

## Error Handling

- Server version fetch failure: silently ignored, `serverVersion` stays `null`, version text absent from UI
- Username empty string: brackets omitted from headline (guard: `if (username.isNotEmpty())`)

## Out of Scope

- Showing version for non-active servers in the switcher dropdown
- Persisting server version to disk
- Any UI feedback for version fetch in-progress
