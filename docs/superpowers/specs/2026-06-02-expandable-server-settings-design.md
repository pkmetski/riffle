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

### Audio cache — make it genuinely global, not per-server (Option A + cleanup)

Today the cache cap is *stored* per Storyteller server id, but the cache itself is a single
shared on-disk store and `enforceCacheCap()` applies only the **active** server's cap to it.
The per-server-ness is an implementation artifact, not real isolation, and would be a lie if
surfaced under each server's expansion.

Resolution: treat the cap as **one global setting** and clean up the code to match.

- It is **not** placed in any server's expansion. It remains its own section
  ("Readaloud audio cache"), shown whenever at least one Storyteller server exists.
- Storage collapses from per-`serverId` keys to a single global key.

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
- `Audio cache` section becomes a single global "Readaloud audio cache" cap dropdown
  (shown when any Storyteller server exists), no longer per-server.

### ViewModel — `app/.../feature/settings/SettingsViewModel.kt`

- `libraryUiItems` currently keys off the **active** server only. For ABS expansions we need
  libraries **per ABS server**. Decision: ABS libraries are still fetched for the active
  server (the library list comes from the active connection); expanding a non-active ABS
  server is out of scope for v1 — only the active ABS server shows its library switches.
  Non-active ABS expansions show a short note ("Activate this server to manage its
  libraries") rather than fetching. *(Confirm during planning — see Open questions.)*
- `audioCacheCaps: StateFlow<Map<String, Long>>` → single `audioCacheCap: StateFlow<Long>`.
- `setAudioCacheCap(serverId, cap)` → `setAudioCacheCap(cap)`.
- Add a matches **summary** stream per Storyteller server (counts of confirmed / pending /
  unmatched) to feed the expansion. Derive from the same source
  `ReadaloudMatchesViewModel` uses (`ReadaloudReview`).

### Cache store — make global

- `AudioCachePreferencesStore`: `capBytes(serverId)` / `setCapBytes(serverId, cap)` →
  `capBytes()` / `setCapBytes(cap)`.
- `AudioCachePreferencesStoreImpl`: single key `longPreferencesKey("audio_cache_cap")`
  instead of `audio_cache_cap_$serverId`. No DataStore migration of old per-server values
  is required (caps are a convenience setting that re-defaults to 2 GB); note this in the PR.
- `ReadaloudAudioRepositoryImpl.enforceCacheCap()`: drop the `getActive()` lookup; read the
  global cap directly.
- Update the KDoc on the store (no longer "Per-Storyteller-Server").

## Testing

- `SettingsViewModelTest`: update for the global `audioCacheCap` / `setAudioCacheCap(cap)`
  signature; add a test for the new matches-summary stream.
- Harness UI test (phone form factor): expanding an ABS server reveals its library switches;
  expanding a Storyteller server reveals the matches summary + navigate row; collapsing hides
  them. Follow existing settings harness-test patterns.
- Existing `ReadaloudMatchesScreen` navigation continues to work from the new entry point.

## Out of scope

- Inlining the full matches UI (rejected — Option A chosen).
- Genuinely partitioning the audio cache per server (Option B from discussion).
- Managing libraries for non-active ABS servers (deferred; see Open questions).

## Open questions (resolve in planning)

1. Non-active ABS server expansion: show a "activate to manage" note vs. fetch libraries for
   any ABS server. Leaning toward the note for v1.
2. Should the global audio-cache section move under a general "Storyteller" heading, or stay a
   top-level "Readaloud audio cache" section? Leaning top-level.
