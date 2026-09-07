# Plan — Reduce O'Reilly bot-detection exposure

**Status:** Proposed (not started). O'Reilly (`SourceType.OREILLY`) is a dev-options-gated, single-user
web source. This doc catalogs *what O'Reilly sees* when Riffle talks to `learning.oreilly.com` and
the concrete work to make that traffic look less like a scripted client. It is the detection-facing
companion to [`oreilly-on-demand-chapter-loading-plan.md`](oreilly-on-demand-chapter-loading-plan.md);
that plan's on-demand model is the single most effective mitigation here, so the two overlap by
design.

## What O'Reilly sees today

Every authenticated call goes through `OReillyApi.authorizedGet` (`core:catalog-oreilly/OReillyApi.kt`)
carrying exactly three headers — `Cookie`, `Accept`, `User-Agent` — over Ktor's OkHttp engine. The
login, by contrast, happens in a real Android WebView. That split is the source of every signal below.

### Signals, loudest first

1. **Bulk full-book fetch pattern (loudest — already tripped).**
   `synthesizeEpub()` pulls **~190 authenticated `?download=false` requests** — every chapter + every
   image/CSS/font — in spine order, ~5 concurrent / 120 ms min interval (~8 req/s sustained), with no
   dwell time. No human reader pulls an entire book's files in seconds. This is the "someone is ripping
   the book" signature DRM/anti-abuse targets; it's what produced the observed `403` /
   "for scripted clients from this IP" / ~2 KB DRM-sample responses. Pacing/backoff *mitigate* the rate
   but can't disguise the pattern — the request volume is inherent to building the whole book at once.

2. **TLS / HTTP2 fingerprint contradicts the UA (fundamentally unfixable from Ktor).**
   We send a Chrome-mobile UA string, but the TLS ClientHello (JA3/JA4) and HTTP/2 settings emitted by
   OkHttp are unmistakably Java/OkHttp, not Chrome. Akamai Bot Manager fingerprints the handshake.
   **UA says Chrome + JA3 says Java = high bot score.** No header tweaking changes this — only a real
   browser engine (the WebView) has a browser-authentic fingerprint.

3. **Static / stale UA + missing browser headers.**
   The UA is a hardcoded constant (`Chrome/125.0`) that never varies, will age out, and does not match
   the actual WebView UA that logged in. We also omit everything a real Chrome always sends: `Referer`,
   `Origin`, `Accept-Language`, `sec-ch-ua*`, `sec-fetch-*`. A Chrome UA with none of Chrome's
   client-hints is trivially flagged.

4. **Cross-client session reuse.**
   The cookie set is harvested from the WebView login and replayed verbatim by a *different* client
   (Ktor). Akamai's `_abck` cookie is normally kept alive by ongoing sensor POSTs from the browser; a
   static, un-refreshed `_abck` used from a non-browser TLS fingerprint degrades and reads as a lifted
   session.

Low risk / out of scope: the `/library/cover/{id}/600/` CDN hits are unauthenticated and
browser-normal; individual search/spine/files JSON calls are innocuous — it's the content-fetch volume
that stands out.

## Work, by leverage

### Tier 1 — kills signal #1 (highest value)

- **On-demand per-chapter loading.** Fetch only the chapter (and its assets) the reader is rendering,
  prefetch one ahead, cache on disk. Removes the bulk burst entirely; traffic then looks like a person
  reading. Full design in
  [`oreilly-on-demand-chapter-loading-plan.md`](oreilly-on-demand-chapter-loading-plan.md). **This is
  the mitigation that matters most** — do not treat the Tier 3 header tweaks as a substitute.
- **Download stays a full synthesis** but becomes an explicit, backgrounded, progress-shown user
  action (already the case) — and should be additionally rate-limited / jittered (see Tier 3).

### Tier 2 — addresses #2 and #4 (robust, larger change)

- **Route content fetches through the WebView.** Reuse the same WebView (or a headless one seeded with
  the login cookies) to issue content requests, so the TLS fingerprint, cookie jar, and Akamai sensor
  state all match what O'Reilly expects from this session. This is the only approach that beats TLS
  fingerprinting and keeps `_abck` fresh. Bigger lift; the fetch/cache logic stays shared in
  `commonMain`, only the transport becomes a platform seam (`expect/actual`) — Android via
  `WebView`/`WebViewClient.shouldInterceptRequest` or `evaluateJavascript(fetch(...))`, iOS via
  `WKWebView` when iOS reading lands.
- If a full WebView proxy is too heavy, a lighter variant: periodically refresh the harvested cookies
  from `CookieManager` before/at each fetch batch so `_abck`/session cookies don't go stale
  mid-session.

### Tier 3 — cheap incremental hardening (lowers score, does NOT beat TLS)

These reduce the header-level tells but leave the TLS fingerprint (#2) intact. Worth doing, but only
as a complement to Tier 1/2 — never as the whole answer.

- **Harvest and reuse the real WebView UA.** Capture `navigator.userAgent` at login (via
  `evaluateJavascript`) and store it with the session; send that exact string on every API call so the
  UA matches the login device instead of a hardcoded constant. Removes the login-vs-API UA mismatch and
  the staleness of `Chrome/125.0`.
- **Send browser-consistent headers.** Add `Referer` (the reader/book URL the request would originate
  from), `Origin: https://learning.oreilly.com`, `Accept-Language`, and — matched to the UA — the
  `sec-ch-ua` / `sec-ch-ua-mobile` / `sec-ch-ua-platform` client hints and `sec-fetch-*`. Keep the
  existing per-content-type `Accept` split (`?download=false` + HTML Accept is required for full
  chapters).
- **Humanize pacing.** Add jitter to the min-interval (e.g. randomize 120 ms → 120–400 ms) and cap
  concurrency lower for the Download path so even a full synthesis doesn't spike to a flat ~8 req/s.
- **Backoff already exists** (`maxContentRetries`, `backoffBaseMs`) — on a `403`/sample response,
  ensure it backs off hard and surfaces "couldn't prepare book" rather than hammering.

## Relevant code

- `core:catalog-oreilly/OReillyApi.kt` — `authorizedGet` (the 3-header transport), UA constant,
  content vs JSON Accept split, endpoint builders. **All header/UA changes land here.**
- `core:catalog-oreilly/OReillyCatalog.kt` — `synthesizeEpub()` (the bulk fetch + `RequestPacer`
  concurrency/interval), `fetchChapterContent`/`fetchAssetBytes` (backoff + truncation detection).
- `OReillyCatalogFactory` + `CoreDataKoinModules.kt` (androidMain) — wires the `STREAMING_HTTP_CLIENT`
  and the `cookieProvider = { CookieManager.getInstance().getCookie(OREILLY_BASE_URL) }`. UA/cookie
  refresh plumbing would extend the `cookieProvider` seam (e.g. add a `userAgentProvider`).
- `app/.../feature/source/oreilly/OReillyLoginViewModel.kt` — WebView login; the place to also harvest
  `navigator.userAgent` (Tier 3) and, for Tier 2, to keep as the live transport.

## Recommendation

1. Ship Tier 3 header/UA/jitter hardening as a small, immediate PR — low risk, reduces the obvious tells.
2. Pursue Tier 1 (on-demand loading) as the real fix — it's the only thing that removes the bulk pattern
   that's actually getting flagged.
3. Consider Tier 2 (WebView-routed fetches) only if Tier 1 + Tier 3 still trip Akamai; it's the
   heaviest change and the only one that addresses TLS fingerprinting.

Given the source is dev-gated and single-user, Tier 3 + Tier 1 is the pragmatic ceiling; Tier 2 is the
belt-and-suspenders option if O'Reilly ever becomes first-class.
