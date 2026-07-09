# Source Switcher, Add Source flow, Source Type picker

Issue: [#435](https://github.com/pkmetski/riffle/issues/435). Phase 3 of the [ADR 0041](../../adr/0041-source-and-service-abstractions-replace-server.md) Source/Service re-root. Amends [ADR 0007](../../adr/0007-navigation-drawer-replaces-server-and-library-screens.md).

## Goal

Rename user-facing surfaces from **Server** to **Source** and introduce a **Source Type picker** as the first step of the Add flow (Audiobookshelf enabled, LocalFiles a disabled placeholder until #7).

## Scope

### In scope

- New `SourceTypePickerScreen` inserted before the ABS Add-Source entry points.
- File and symbol renames:
  - `AddServerScreen` → `AddSourceScreen`
  - `AddServerViewModel` → `AddSourceViewModel`
  - `AddServerBackend` → `AddSourceBackend` (enum values `AUDIOBOOKSHELF` / `STORYTELLER` / `WEBDAV` unchanged)
  - `ServerSetupViewModel` → `SourceSetupViewModel`
  - Nav-route consts `ADD_SERVER*` → `ADD_SOURCE*`; new `ADD_SOURCE_TYPE_PICKER`
  - Callback params `onNavigateToAddServer` → `onNavigateToAddSource`
- User-visible string changes:
  - Drawer: `"No server"` → `"No source"`; `"Toggle server switcher"` contentDescription → `"Toggle source switcher"`
  - Settings: section header `"Audiobookshelf servers"` → `"Sources"`; button `"Add server"` → `"Add source"`
  - AddSourceScreen (ABS variant only): title `"Add Audiobookshelf server"` / `"Edit Audiobookshelf server"` → `"Add Audiobookshelf"` / `"Edit Audiobookshelf"`; remove-button `"Remove server"` → `"Remove source"`
- Instrumentation snapshot tests regenerated where affected.

### Out of scope

- Storyteller user-facing strings (`"Add Storyteller"`, "Edit Storyteller", etc.) stay unchanged — issue #441 renames them.
- WebDAV strings stay — WebDAV is a Service (annotation sync target), not a Source.
- `Source.serverType` domain field stays (the `AUDIOBOOKSHELF` / `STORYTELLER` split is what #441 collapses).
- ABS product-referencing copy stays ("your Audiobookshelf server" in help text; "Couldn't reach the server" errors) — these refer to ABS the product.
- Directory `app/feature/server/` stays — package rename is churn without user impact.

## Non-goals

- No behavioral change to the ABS or Storyteller connection flows.
- No LocalFiles wiring; the card is a visible placeholder only.
- No Storyteller-as-Service copy shift (issue #441).

## Design

### Source Type picker screen

**Route:** `add_source_type_picker`, inside the source-setup nav graph. Reachable only from ABS entry points (see wiring below). Storyteller and WebDAV deep-link straight into `AddSourceScreen` with their preset backend.

**Composable:** `SourceTypePickerScreen(onPickAudiobookshelf: () -> Unit, onNavigateBack: () -> Unit)`

- `Scaffold` + `TopAppBar` titled `"Add source"` with back arrow.
- `TabletContentWidthContainer` (matches existing Add-Source screens).
- Vertical stack of two `Card`s.

**Card 1 — Audiobookshelf**

- Leading icon: cloud/storage placeholder.
- Headline: `"Audiobookshelf"`.
- Supporting: `"Stream ebooks and audiobooks from your Audiobookshelf server."`
- `enabled = true`; `onClick` → `onPickAudiobookshelf()`.

**Card 2 — Local files**

- Leading icon: folder placeholder.
- Headline: `"Local files"`.
- Supporting: `"Read EPUBs and PDFs from a folder on this device."`
- Trailing: plain rounded label (`Surface` with `RoundedCornerShape` + `tertiaryContainer` background) reading `"Coming soon"`.
- `enabled = false`; no `onClick` modifier. Content dimmed via `alpha 0.5f` on the leading icon + text.

**Data model** — pure Kotlin, JVM-testable. `SourceType` currently only has `ABS`; adding `LOCAL_FILES` belongs to #7 / #438. This PR uses a picker-local sealed interface so the LocalFiles row exists without dragging a domain-model change:

```kotlin
sealed interface SourceTypeChoice {
    data object Audiobookshelf : SourceTypeChoice
    data object LocalFiles : SourceTypeChoice
}

data class SourceTypeCard(
    val type: SourceTypeChoice,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    val comingSoon: Boolean,
)

internal fun sourceTypeCards(): List<SourceTypeCard> = listOf(
    SourceTypeCard(
        type = SourceTypeChoice.Audiobookshelf,
        title = "Audiobookshelf",
        subtitle = "Stream ebooks and audiobooks from your Audiobookshelf server.",
        enabled = true,
        comingSoon = false,
    ),
    SourceTypeCard(
        type = SourceTypeChoice.LocalFiles,
        title = "Local files",
        subtitle = "Read EPUBs and PDFs from a folder on this device.",
        enabled = false,
        comingSoon = true,
    ),
)
```

### Nav-graph wiring

**Entry points routed through the picker** (change from direct → picker):

1. Nav Drawer `+ Add source` button (drawer copy: `"Add server"` → `"Add source"`).
2. Settings `"Sources"` section `"Add source"` button.
3. `HomeViewModel.StartDestination.AddSource` (fresh install with no configured sources).

**Entry points that keep deep-linking directly** (unchanged):

1. Settings → Readaloud → `"Configure Storyteller"` → `AddSourceScreen(backend=STORYTELLER)`.
2. Settings → Sync → WebDAV row → `AddSourceScreen(backend=WEBDAV)`.
3. Editing an existing ABS Source (Settings row → tap) → `AddSourceScreen(backend=AUDIOBOOKSHELF, editId=<id>)`.

**In `MainScreen.kt`:**

- Rename `SERVER_SETUP_GRAPH` → `SOURCE_SETUP_GRAPH`, `ADD_SERVER` → `ADD_SOURCE`, `ADD_SERVER_ROUTE` → `ADD_SOURCE_ROUTE`.
- Add const `ADD_SOURCE_TYPE_PICKER = "add_source_type_picker"` and a composable inside the setup graph.
- Picker's `onPickAudiobookshelf` navigates to `AddSourceScreen(backend=AUDIOBOOKSHELF, editId=null)` with `popUpTo(ADD_SOURCE_TYPE_PICKER) { inclusive = true }` so back from the form returns to the caller (drawer/settings), not the picker.

## Test plan

### JVM unit tests

**`SourceTypePickerTest`** (new)

- `cards_orderIsAbsThenLocalFiles()` — `sourceTypeCards()` returns two items, ABS first, LocalFiles second. Reverting the order flips red.
- `abs_isEnabled_notComingSoon()` — ABS card `enabled=true, comingSoon=false`.
- `localFiles_isDisabled_showsComingSoon()` — LocalFiles card `enabled=false, comingSoon=true`. Reverting LocalFiles to enabled flips red — this is the pinned contract until #7 lands.

**`AddSourceViewModelTest`** (existing, renamed from `AddServerViewModelTest`) — symbol-only rename; behavior unchanged.

### Instrumentation tests

**`SourceTypePickerScreenTest`** (new)

- `bothCards_areDisplayed()` — asserts `"Audiobookshelf"` and `"Local files"` headline text render.
- `audiobookshelfCard_click_invokesCallback()` — tap on the Audiobookshelf card fires `onPickAudiobookshelf` exactly once.
- `localFilesCard_isDisabled_showsComingSoon()` — asserts `"Coming soon"` text is visible; asserts the LocalFiles card `.assertHasNoClickAction()`.

**Existing drawer/settings tests updated for renamed copy:**

- `NavigationDrawerGestureTest`
- `PermanentNavigationDrawerTest`
- `NavigationDrawerViewModelTest` (if it asserts on copy)
- Any harness test that traverses the Add flow needs the extra picker tap.

### Regression pinning (what stops a revert)

- `SourceTypePickerTest.localFiles_isDisabled_showsComingSoon()` and `SourceTypePickerScreenTest.localFilesCard_isDisabled_showsComingSoon()` — pin the LocalFiles-disabled state.
- Drawer/settings copy assertions in updated instrumentation tests — pin the string rename.

## Verification (per AGENTS.md)

This is a UI-visible change: verification path is **AVD build + install** with before/after frames of the Nav Drawer header, Settings → Sources section, and the new Source Type picker screen at phone and tablet form factors, plus one edit-flow trip (deep-link into an existing ABS source) to confirm the picker is bypassed on edit.

## Risks and mitigations

- **Snapshot test churn:** many drawer/settings snapshot tests may need regeneration. Mitigation: run harness suite, regen failing snapshots, review each diff to confirm the only changed pixels are the copy rename.
- **Nav route rename breaking deep links:** nav routes are ephemeral (in-memory backstack); no persisted state references them. No migration needed.
- **AddSourceBackend enum retains `STORYTELLER`/`WEBDAV`:** internal name is inconsistent with the ADR (Storyteller/WebDAV are Services). Deliberately deferred to #441; documented above.
