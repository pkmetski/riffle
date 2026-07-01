# ReaderSessionLifecycle — Design

Extract book-open/close lifecycle and matched-book cross-sync state out of `EpubReaderViewModel` into a `ReaderSessionLifecycle` collaborator. Narrower re-scoping of GitHub issue #376.

## Context

Issue #376 originally called for splitting `EpubReaderViewModel` (1,591 lines, ~40 injected deps) into `ReaderSession`, `FormatCompositor`, and further splitting `PositionOrchestrator` into `ServerJumpCoordinator` + `ResumeRestorer`.

The four last items are already shipped in `app/src/main/kotlin/com/riffle/app/feature/reader/session/`:

- `PositionOrchestrator` (moved out of VM)
- `ServerJumpCoordinator` and `ResumeRestorer` (split inside PositionOrchestrator's package)
- `FormattingSession` + separate `WakeLockController` / `SearchController` (the "FormatCompositor" role, split as three seams rather than one composite — chosen because wake-lock has a different dependency and lifecycle than typography)
- `AnnotationSession`, `ReadaloudSession`, `BookmarksController`, `VolumeKeyDispatcher`

What remains bloating the VM is the book-open pipeline and its shared state. This spec extracts that.

## Scope

**In:** move book-open orchestration, close-side teardown, and matched-book cross-sync state onto a new `ReaderSessionLifecycle` collaborator.

**Out:**

- Legacy raw-`epubcfi(...)` healing (needs VM-private `cfiStringToLocator`) — follow-up.
- Footnote-popup origin capture — follow-up.
- Compose-facing state assembly cleanup — follow-up.
- Any behavior change. This is a pure refactor.

## The seam

New file: `app/src/main/kotlin/com/riffle/app/feature/reader/session/ReaderSessionLifecycle.kt`.

State owned by the lifecycle (moved off the VM):

- `publication: StateFlow<Publication?>` — null until `open()` resolves; observable so future Compose-facing code can react without threading a snapshot.
- `epubFile: File?`, `epubZip: ZipFile?` (with lazy-cached `zipFor(file)` accessor).
- `matchedSync: StateFlow<MatchedSync?>` bundling `readerSync`, `audiobookFollow`, `serverId`.
- `closeSyncDone: Boolean` (idempotency guard for close).

Constructor deps lifted from the VM (into `ReaderSessionLifecycle`'s assisted-factory):

`libraryObserver`, `epubRepository`, `serverRepository`, `readaloudLinkRepository`, `audioIdentityResolver`, `audioPlaybackPreferencesStore`, `listeningPreferencesStore`, `openReconcileTargets`, `readerSyncFactory`, `annotationStore`, `logger`.

## Interface

```kotlin
class ReaderSessionLifecycle @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    private val libraryObserver: LibraryObserver,
    private val epubRepository: EpubRepository,
    private val serverRepository: ServerRepository,
    private val readaloudLinkRepository: ReadaloudLinkRepository,
    private val audioIdentityResolver: AudioIdentityResolver,
    private val audioPlaybackPreferencesStore: AudioPlaybackPreferencesStore,
    private val listeningPreferencesStore: ListeningPreferencesStore,
    private val openReconcileTargets: OpenReconcileTargets,
    private val readerSyncFactory: ReaderSyncFactory,
    private val annotationStore: AnnotationStore,
    private val logger: Logger,
    // Publication opening is passed as a lambda so the lifecycle stays free of
    // AssetRetriever/PublicationOpener plumbing; the VM already owns openPublication().
    private val openPublication: suspend (File) -> Publication?,
    // Legacy raw-`epubcfi(...)` translation — needed at open time to resolve openAtCfi.
    // Left as a lambda for now; a follow-up lifts cfiStringToLocator into the lifecycle.
    private val cfiStringToLocator: (String) -> Locator?,
) {
    @AssistedFactory
    interface Factory {
        fun create(
            scope: CoroutineScope,
            openPublication: suspend (File) -> Publication?,
            cfiStringToLocator: (String) -> Locator?,
        ): ReaderSessionLifecycle
    }

    val publication: StateFlow<Publication?>
    val matchedSync: StateFlow<MatchedSync?>

    suspend fun open(params: OpenParams): OpenOutcome
    suspend fun close()          // idempotent
    fun zipFor(file: File): ZipFile
    fun onCleared()
}

data class OpenParams(
    val itemId: String,
    val openAtCfi: String?,
    val startTocHref: String?,
)

sealed interface OpenOutcome {
    data class Ready(
        val publication: Publication,
        val title: String,
        val initialLocator: Locator?,
        val initialFocusAnnotationId: String?,
        val openAtLocator: Locator?,
        val activeServer: Server?,
        val isStorytellerServer: Boolean,
        val resolvedAudioBookId: String,
        val resolvedAudioServerId: String,
        val resolvedReaderServerId: String?,
        val resolvedAudiobookItemId: String?,
        val resolvedAudioSettingsIdentity: AudioIdentity,
        val resolvedInitialSpeed: Float,
    ) : OpenOutcome

    data class Error(val message: String) : OpenOutcome
}

data class MatchedSync(
    val readerSync: ReaderSyncCoordinator?,
    val audiobookFollow: AudiobookFollow?,
    val serverId: String?,
)
```

## What the VM keeps

- Bind orchestration. After `lifecycle.open(...)` returns `Ready`, the VM calls `readaloud.bind(...)`, `position.bindBook(...)`, `bookmarks.bind(...)`, `search.bind(publication)`, then emits `ReaderState.Ready`. This preserves the Compose-facing composer role and does not push Readium types into the lifecycle beyond `Publication` / `Locator`.
- Publication-touching helpers (footnote resolution, CFI translation, spine-position counts). Their signatures do not change; they read `lifecycle.publication.value ?: return` instead of the removed VM field. Follow-ups may lift some of these later.
- Providers threaded into other sessions become `{ lifecycle.matchedSync.value?.readerSync }` / `{ lifecycle.matchedSync.value?.audiobookFollow }` / `{ lifecycle.matchedSync.value?.serverId }`.
- Handoff auto-start (`startReadaloudAtSec`). The lifecycle does not touch `readaloud`; the VM calls `startReadaloudAtSecond(...)` post-`Ready` as today.
- TOC nav on open (`startTocHref`). The lifecycle returns it via `Ready.initialLocator = null` when the flag is set (matching the existing "let navigateToEntry own it" comment); the VM calls `navigateToEntry(...)` post-`Ready`.
- The Horizontal-only paged post-open annotation-nav emit (`annotationSession.emitAnnotationNavigation(openAtLocator)`). Stays in the VM because it depends on `formatting.effectiveFormattingPreferences.value.orientation`.
- `markSuppressNextServerLocator()` when `openAtLocator != null`. Stays in the VM because `position` is a VM-held collaborator; the lifecycle just returns `openAtLocator` in `Ready`.

## Close path

`lifecycle.close()`:

1. Return immediately if `closeSyncDone`.
2. Set `closeSyncDone = true`.
3. Call `openReconcileTargets.markClosed(serverId, itemId)` for the reader item and (if resolved) the audio item.
4. No sync writes — sync teardown remains in the VM's `performCloseSync()` path (ADR 0030's ordering with `progressFlushScope` is delicate; touching it is out of scope).

`lifecycle.onCleared()`: clears `epubZip`, `publication`.

## Testing

New `app/src/test/kotlin/.../session/ReaderSessionLifecycleTest.kt` (JVM):

- Open happy path against a Storyteller server.
- Open ABS with matched readaloud link → resolves `audioBookId`, `audioServerId`, `audiobookItemId` from `readaloudLinkRepository`.
- Open with `openAtCfi` non-blank → returns `openAtLocator` non-null and `initialFocusAnnotationId` from `annotationStore.findByItemAndCfi`.
- Open with `startTocHref` non-null → `Ready.initialLocator` is null.
- Open failure: item not found → `Error("Book not found")`.
- Open failure: EPUB open returns null publication → `Error("Failed to open EPUB")`.
- `close()` is idempotent (second call is a no-op).
- `matchedSync` reflects `readerSyncFactory.createIfApplicable(itemId)` success and its `null` fallback path (`createAudiobookFollowIfApplicable`).

Existing `EpubReaderViewModelTest` cases involving the open flow stay green — the lifecycle is a collaborator behind an assisted factory and is replaced with a fake in VM tests.

No instrumentation. This seam does not touch Readium's WebView, the navigator, or JS injection. JVM tests are the AGENTS.md-approved validation tier for this refactor.

## Rollout

Single PR, single commit. Behavior-preserving. No user-visible change; no ADR update; no migration.

PR body includes `Closes #376` and a "Deferred to follow-ups" section listing:

- `cfiStringToLocator` + legacy-CFI healing → lifecycle
- Footnote-popup origin capture → lifecycle
- Compose-facing state assembly cleanup on the VM
