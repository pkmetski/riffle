# Notification tap opens the active player view

## Problem

While an audiobook or readaloud book is playing, tapping the Android media
notification / lock-screen player brings Riffle to the foreground but does not
reliably land on the player view. We want the tap to navigate to whichever
player is currently active — the full-screen audiobook player, or the EPUB
reader with the active readaloud session.

## Relevant facts about the current code

- One shared `AudioPlayerService` (a Media3 `MediaSessionService`) backs both
  audiobook and readaloud playback. Its `MediaSession` now sets a session
  activity, but it points at the bare launcher intent, so the tap only resumes
  the task at whatever screen it was last on.
- There is **no persistent now-playing mini-bar** (deferred in ADR 0029).
  - `AudiobookPlayerViewModel.onCleared()` calls `controller.stop()`.
  - `EpubReaderViewModel.onCleared()` calls `playerCoordinator.close()` →
    `ReadaloudController.stop()`.
  - Consequence: audio of either kind only plays while its own screen is the
    current Compose destination and `MainActivity` is alive. Whenever a media
    notification exists to tap, the process is alive (foreground service), so an
    app-scoped in-memory store is sufficient — it need not survive process death.
- Audiobook player route: `audiobook_player/{itemId}` (itemId URL-encoded).
- Readaloud has no separate route; it lives inside `epub_reader/{itemId}`.
- `MainActivity` hosts the Compose `NavHost` (`MainScreen`). It currently has no
  `onNewIntent` override and uses default (`standard`) launch mode.
- Existing pattern to drive navigation from `MainActivity` into Compose: the
  injected `VolumeNavigationController` exposes a flow that `MainScreen` collects.

## Design (Approach A)

### 1. `NowPlayingStore` — Hilt `@Singleton`

Single source of truth for the active session:

```kotlin
sealed interface NowPlaying {
    val itemId: String
    data class Audiobook(override val itemId: String) : NowPlaying
    data class Readaloud(override val itemId: String) : NowPlaying
}

@Singleton
class NowPlayingStore @Inject constructor() {
    @Volatile var current: NowPlaying? = null
        private set
    fun set(value: NowPlaying) { current = value }
    fun clearIf(predicate: (NowPlaying) -> Boolean) {
        if (current?.let(predicate) == true) current = null
    }
}
```

- `AudiobookPlayerViewModel` calls `set(Audiobook(itemId))` when it prepares
  playback; `onCleared()` calls `clearIf { it is Audiobook && it.itemId == itemId }`.
- `EpubReaderViewModel` calls `set(Readaloud(itemId))` when readaloud starts;
  `onCleared()` calls `clearIf { it is Readaloud && it.itemId == itemId }`.

The `clearIf` guard keeps a plain reader close (or any non-matching teardown)
from wiping an unrelated entry. App-scoped lifetime means the store survives
`MainActivity` recreation.

### 2. Session-activity `PendingIntent`

In `AudioPlayerService`, replace the launcher intent with an explicit one:

```kotlin
private fun openRiffleIntent(): PendingIntent {
    val intent = Intent(this, MainActivity::class.java)
        .setAction(ACTION_OPEN_NOW_PLAYING)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
}
```

`ACTION_OPEN_NOW_PLAYING` is a constant (e.g. `"com.riffle.app.action.OPEN_NOW_PLAYING"`).
The intent carries no per-item data — the dynamic target is read from
`NowPlayingStore` at tap time.

`MainActivity` is declared `android:launchMode="singleTop"` so an existing
instance receives `onNewIntent` rather than being recreated.

### 3. Navigation bridge

New injected `@Singleton NowPlayingNavigator`, mirroring
`VolumeNavigationController`:

```kotlin
@Singleton
class NowPlayingNavigator @Inject constructor() {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: SharedFlow<Unit> = _events
    fun requestOpen() { _events.tryEmit(Unit) }
}
```

- `MainActivity` handles the intent in both `onCreate` (cold/recreated) and
  `onNewIntent` (warm): if `intent.action == ACTION_OPEN_NOW_PLAYING`, call
  `nowPlayingNavigator.requestOpen()`.
- `MainScreen` collects `events`, reads `NowPlayingStore.current`, and navigates:
  - `Audiobook(id)` → `audiobook_player/{encoded id}`
  - `Readaloud(id)` → `epub_reader/{encoded id}`
  - `null` → no-op.
  - Use `launchSingleTop = true` so re-navigating to the screen you're already on
    is a no-op (no playback restart).

## Behaviour / tradeoffs

- Warm case (player/reader still the current destination): the collected event
  navigates to the same route with `launchSingleTop`, a no-op — audio is
  untouched and you simply see the screen again.
- If the nav state is somewhere else (e.g. activity was recreated to the start
  destination), navigating fresh to the audiobook player re-prepares from the
  server resume position — a brief reseek, not a restart. Acceptable.
- Readaloud reopened fresh resumes from the saved sentence/position but does not
  auto-start playback; in practice readaloud's screen is always the current
  destination while it plays, so this path is the warm no-op.

## Out of scope

- Surviving process death (no notification exists to tap once the foreground
  service is killed).
- A persistent now-playing mini-bar (still deferred per ADR 0029).

## Testing

- Unit-test `NowPlayingStore` set/`clearIf` semantics (matching vs non-matching
  clears).
- Unit-test the route-selection mapping (NowPlaying → route string) if it is
  extracted as a pure function.
- Manual device verification: play audiobook → Home → tap notification → lands
  on audiobook player; play readaloud → Home → tap notification → lands in reader
  with readaloud active.
