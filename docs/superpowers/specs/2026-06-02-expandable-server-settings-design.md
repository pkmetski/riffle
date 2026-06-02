# Expandable per-server settings — design

**Date:** 2026-06-02
**Branch:** `pkmetski/expandable-server-settings`

## Problem

In the Settings screen, server-specific settings are scattered as sibling sections
(`Servers`, `Libraries`, `Readaloud matches`, `Audio cache`) rather than attached to the
server they belong to. The user must infer which server each section governs, and
`Libraries` only ever reflects the *active* server. This is confusing and doesn't scale as
more servers are added.

## Goal

Make each server row in the `Servers` section an **expandable disclosure**. Tapping a row
unfolds that server's own settings beneath it:

- **Audiobookshelf** → **Enabled libraries** (visibility switches)
- **Storyteller** → **Readaloud matches** (summary + entry point to the full matches screen)

The standalone `Libraries`, `Readaloud matches` sibling sections go away — their content
moves into the relevant server's expansion.

## Scope decisions

### Storyteller readaloud matches — stay a separate screen (Option A)

The matches experience (`ReadaloudMatchesScreen`) is complex: Pending / Unmatched /
Confirmed lists, per-item confirm/dismiss, cover images, and a manual-pairing search dialog.
It stays a **dedicated screen**. The Storyteller expansion shows a compact **summary**
(e.g. "3 confirmed · 1 pending review · 2 unmatched") plus a **"Review & pair readalouds →"**
row that navigates to the existing screen. The expansion only relocates the entry point under
its server; it does not inline the matches UI.

### Audio cache — left untouched, out of scope

The readaloud audio-cache cap is **not part of this task**. It only ever governs Storyteller
readaloud audio (ABS EPUB/PDF books live in entirely separate cache/download stores with no
app-level cap), and making it genuinely global — let alone unifying it with the book caches —
is a separate piece of work.

Resolution: the existing `Audio cache` section stays **exactly as it is today** (its own
section, per-Storyteller-server dropdowns). It is **not** moved into any server expansion and
no cache code is changed.

## Affected code

### UI — `app/.../feature/settings/SettingsScreen.kt`

- Servers section: each `ListItem` gains a leading chevron and click-to-expand behavior.
  Keep the existing `SwipeToDismissBox` delete. Track expanded state per server id
  (`remember { mutableStateMapOf<String, Boolean>() }` or similar). Animate with
  `AnimatedVisibility` / `animateContentSize` (patterns already used elsewhere in the app).
- ABS expansion renders the library switches (currently the standalone `Libraries` section).
- Storyteller expansion renders the matches summary + navigate row (currently the standalone
  `Readaloud matches` section).
- Remove the standalone `Libraries` and `Readaloud matches` sections.
- Leave the `Audio cache` section exactly as-is (out of scope).

### ViewModel — `app/.../feature/settings/SettingsViewModel.kt`

- `libraryUiItems` currently keys off the **active** server only. For ABS expansions we need
  libraries **per ABS server**. Decision: ABS libraries are still fetched for the active
  server (the library list comes from the active connection); expanding a non-active ABS
  server is out of scope for v1 — only the active ABS server shows its library switches.
  Non-active ABS expansions show a short note ("Activate this server to manage its
  libraries") rather than fetching. *(Confirm during planning — see Open questions.)*
- `audioCacheCaps` / `setAudioCacheCap` are left unchanged (cache out of scope).
- Add a matches **summary** stream per Storyteller server (counts of confirmed / pending /
  unmatched) to feed the expansion. Derive from the same source
  `ReadaloudMatchesViewModel` uses (`ReadaloudReview`).

### Cache store — unchanged

The audio-cache store, preferences, and `enforceCacheCap()` are **not touched** by this task.

## Testing

- `SettingsViewModelTest`: add a test for the new matches-summary stream. Audio-cache tests
  stay as-is.
- Harness UI test (phone form factor): expanding an ABS server reveals its library switches;
  expanding a Storyteller server reveals the matches summary + navigate row; collapsing hides
  them. Follow existing settings harness-test patterns.
- Existing `ReadaloudMatchesScreen` navigation continues to work from the new entry point.

## Out of scope

- Inlining the full matches UI (rejected — Option A chosen).
- Any change to the readaloud audio-cache (global cap, per-server partitioning, or a unified
  app-wide cap spanning the EPUB/PDF book caches) — all deferred as separate work.
- Managing libraries for non-active ABS servers (deferred; see Open questions).

## Open questions (resolve in planning)

1. Non-active ABS server expansion: show a "activate to manage" note vs. fetch libraries for
   any ABS server. Leaning toward the note for v1.
