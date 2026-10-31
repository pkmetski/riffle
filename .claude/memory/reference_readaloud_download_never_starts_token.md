---
name: reference_readaloud_download_never_starts_token
description: "Readaloud download 'spins forever, % never starts' = pre-first-byte window: openBundleStream execute() blocks ~31s+ (Storyteller /synced prepares the whole 1.25GB bundle before byte 0) with NO progress + NO timeout (readTimeout/callTimeout=0); onProgress can't fire until first byte so the ring stays indeterminate. Separate, rarer mode: null Storyteller token → instant 'Couldn't download readaloud audio' snackbar."
metadata: 
  node_type: memory
  type: reference
  originSessionId: 70d14d09-7c5f-4711-91cf-a239955cf386
---

Diagnosed end-to-end on emulator (branch pkmetski/readaloud-bundle-no-start), book "AI Engineering" (ABS item 21962a61-a381-447e-8ac1-e48cd4395703, Storyteller book 6712934087641600, bundle **1,249,767,826 B = 1.25GB**). The download path itself is correct — full bundle landed byte-exact in ~2-3 min once it gets going.

**THE user-reported symptom = "button keeps spinning, % count never starts" → the pre-first-byte window (CONFIRMED with in-app instrumentation):**
- `AudiobookBundleApiImpl.openBundleStream` calls `execute()`, which BLOCKS until Storyteller sends response headers. Measured in-app: **execute() RETURNED after 30680ms**, code=200, Content-Length=1249767826 (NOT chunked — total IS known). Storyteller `/synced` lazily generates the entire bundle before byte 0 (see [[reference_storyteller_synced_slow_first_byte]]).
- The progress `onProgress(written,total)` is only called inside the body read loop, i.e. AFTER the first byte. So during the whole ~31s (the user's server: far longer — bigger/cold bundle, slower disk, or it stalls) the button is `InProgress(percent=null)` = **indeterminate spinner with NO %**. First byte → pct jumps to 4% within ~2s and climbs normally.
- NO upper bound: `bundleClient` sets `readTimeout(0)` + `callTimeout(0)` (only 30s CONNECT timeout, which covers TCP only, not the header wait). A slow/stalled bundle-prep → spins indefinitely. And `DownloadManager.start` is idempotent so re-taps are silent no-ops; only escape is app restart.
- Fix: show a distinct "Preparing on server…"/indeterminate-with-label state vs a real %; add a finite-but-generous bound (must tolerate 30-60s+ TTFB yet eventually fail→snackbar instead of wedging); make the in-progress button cancellable. Deeper fix = stream audio from ABS instead of downloading the 1.25GB bundle (see [[reference_storyteller_v2_and_range_extraction]]).

**SEPARATE, rarer mode (instant fail, NOT what this user saw): null Storyteller token.** `ReadaloudAudioRepositoryImpl.downloadAudio` does `tokenStorage.getToken(link.storytellerServerId) ?: return NetworkError` before any network → instant generic snackbar **"Couldn't download readaloud audio"**, no spinner, no .part. Triggers when the link's storytellerServerId has no token row: Storyteller server removed+re-added (commit() mints a NEW UUID → old `readaloud_links.storytellerServerId` orphaned), token store wiped, etc. Token IS saved for both server types at add (`ServerRepositoryImpl:148`); `adb install -r` does NOT wipe it (the clone's nulls were pre-existing DB/token drift across sessions).

E2E test recipe lessons: clone repro AVD `Riffle_RA_Repro`→session-unique (its DB/token store had drifted; tokens keyed by serverId, AES256_SIV so prefs keys unreadable). To get a valid token under the link's id: re-add Storyteller in-app (new id + fresh token) then UPDATE readaloud_links.storytellerServerId via DB. `run-as` absolute-path `stat` gives false negatives on this API-25 image — use relative paths from the app-home cwd or `find`. core:network is pure-JVM → use `println` not `android.util.Log`. Relates to [[reference_readaloud_e2e_test_setup]], [[project_readaloud_audiobook_sync_gap]].
