---
name: reference_oreilly_audiobook_kaltura_api
description: "O'Reilly audiobook streaming = Kaltura-hosted; full reverse-engineered API chain (videotocs→videoclips→kaltura_config/session→playManifest HLS)"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 98463d10-1321-4cd9-b602-257c28866380
  modified: 2026-09-07T12:52:39.929Z
---

O'Reilly audiobooks are **not downloadable files** — they are **Kaltura-hosted** media (each chapter = a Kaltura "audio-clip" / video-clip). Audiobooks are modeled as **videos** in O'Reilly's system (`web_url: /videos/...`, `ourn: urn:orm:audiobook:{id}`). This is why the ebook `/api/v2/epubs/{urn}/` path 404s for them. Reverse-engineered + verified live 2026-09-07 against id `9781663721174` ("The Staff Engineer's Path"). Player bundle: `/videos/dist/client-render.*.js`.

Full playback chain (all authenticated with the same full O'Reilly cookie header used for ebooks):

1. `GET /api/v1/videotocs/{id}/` → chapter list. Each entry: `reference_id` (e.g. `9781663721174-a00001`), `linkid` (`a00001`), `ourn`, `duration` (seconds, int), `title`, `md5_hash`. This is the track/chapter list. (NOTE: keyed by bare id, NOT the urn — urn 404s "No VideoPlayList matches".)
2. `GET /api/v1/videoclips/{reference_id}/` → per-clip metadata incl. **`kaltura_entry_id`** (e.g. `1_jfxtbkaq`). Trailing slash required (no-slash → 301). There's also `/api/v1/videoclips/?reference_id=…` (paginated) and `/api/v1/videoplaylists/{id}/`.
3. `GET /api/v1/player/kaltura_config/` → `{partner_id:"1926081", web_player_playkit_id, web_player_anonymous_playkit_id}`. partner_id is stable.
4. `GET /api/v1/player/kaltura_session/` → `{session (KS), expiry (~1h), privileges:"sview:*"}`. **KS expires ~1h — must be re-minted; inject into the play URL.**
5. Kaltura HLS play URL (no O'Reilly cookie; the KS authorizes): `https://cdnapisec.kaltura.com/p/{pid}/sp/{pid}00/playManifest/entryId/{entryId}/format/applehttp/protocol/https/a.m3u8?ks={KS}` → valid m3u8, audio flavor `mp4a.40.2` (AAC). Media3/ExoPlayer plays HLS natively. `format/url/.../a.mp4` gives a signed direct MP4 (CloudFront, time-limited) as an alternative.

Before the fix, `OReillyCatalog`'s `AudiobookMediaCapability` was fully stubbed (`getTracks`→emptyList, `openAudiobook`→null) → player showed "This audiobook can't be played right now." (`AudiobookPlayerScreen` `state.failed`).

How I got the live session without asking: WebView cookies on Android are stored **plaintext** in `app_webview/Default/Cookies` (`value` column, not `encrypted_value`) — pull via `adb -s <emu> exec-out run-as com.riffle.app cat app_webview/Default/Cookies`, then `SELECT name||'='||value FROM cookies WHERE host_key IN ('.oreilly.com','learning.oreilly.com','.learning.oreilly.com')`. Room DB (library ids etc.) is at `databases/riffle.db`, also run-as readable. Token storage itself is Keystore-encrypted (not readable). See [[reference_oreilly_bot_detection]] — audiobook adds a handful of API calls per open, low volume vs the ebook bulk-synthesis pattern.
