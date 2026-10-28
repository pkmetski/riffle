---
name: reference_readaloud_e2e_test_setup
description: Recipe to stand up a readaloud book end-to-end on a dev emulator (for testing Play-from-here etc.)
metadata: 
  node_type: memory
  type: reference
  originSessionId: 5e8a79d0-eef8-43fa-931f-a22b951faccc
---

To exercise readaloud (Play-from-here, highlight, auto-follow) live on an emulator:

1. **Build & install YOUR branch first.** The emulators often carry a stale APK from `main`. Symptom of stale build: the live WebView has no `window.__riffleSelRect` after a selection (check via DevTools), or DB-version crash `A migration from N to N-1 was required` on launch (your branch is *behind* main's Room version — pm clear to get a fresh DB at your version, then redo setup). Build: `./gradlew :app:assembleDebug` (set JAVA_HOME, see [[reference_java_home_android_studio]]); install `-r -d`.
2. Bridge to the Tailscale server — see [[reference_emulator_tailscale_bridge]].
3. Add **Audiobookshelf** as the primary server (URL `http://127.0.0.1:13378`, test/test), select libraries. Then add **Storyteller** peer (`http://127.0.0.1:8001`, test/test). ABS must be primary; readaloud links are ABS-item↔Storyteller-book matches (`readaloud_links` table, CONFIRMED rows).
4. Open the **Books** library; books that match a Storyteller readaloud show "Has readaloud (synced narration)". "The Martian" works (matches Storyteller book id 1385738337074647).
5. Download the ebook (small) via the UI. The **readaloud "synced" bundle is huge** (The Martian = 315 MB — it embeds the audiobook audio, ADR 0023) and the in-app download times out over Tailscale. Instead download once on the host (`curl .../api/books/<id>/synced`, ~2-3 min) and `adb push` it to `files/downloads/epubs/<storytellerServerId>/<storytellerBookId>.epub` (LocalStoreImpl layout `dir/<serverId>/<itemId>.epub`; the readaloud store IS the epub store). serverId/bookId come from the `readaloud_links` row.
6. Drive selection + Play-from-here. Inspect resolver state live via WebView DevTools: `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`, then CDP `Runtime.evaluate` (use python `websocket-client`). After a long-press, `window.__riffleSelRect` should be `{top,left,bottom}` and `resolveSelectionSentenceJs` returns the sentence span id.

Find a word's device tap coords from the WebView: `range.getBoundingClientRect()` (CSS px) × `devicePixelRatio` (reader WebView is fullscreen, offset ~0).
