---
name: reference_readaloud_highlight_text_anchored
description: Why the readaloud synced highlight vanished and how it was fixed (text-anchored locator from the Storyteller bundle)
metadata: 
  node_type: memory
  type: reference
  originSessionId: 70e80f6c-ef7b-40d9-839c-4c495a0bbaf3
---

The readaloud synced highlight disappearing was **not** a Readium-version bug (cf. [[reference_readium_33_highlight_regression]], which is the *separate* 2-column-layout issue). Two real causes, found on the API-25 harness by logging the decoration apply + a JS probe:

1. **Readium strips the EPUB's media-overlay sentence spans** (`<span id="…-sN">`) from the HTML it *serves* — proven via `fetch(location.href)` returning the page with those id-only spans unwrapped (it keeps class-bearing spans). So a highlight that targets `#id259-s0` via cssSelector has nothing to attach to. The sentence *text* is still in the served DOM.
2. The regression that exposed it was **#92 / ADR 0026** (`e5e36fd`, on main): it made the reader render the **ABS ebook** instead of the Storyteller bundle. Bisect-proven — highlight works at #91 (renders the bundle, spans intact), dead at #92.

**Fix (text-anchored highlight):** give the readaloud `Locator` the sentence's `text.{before,highlight,after}`; Readium's reflowable JS (`P(u)`) falls back to a `document.body` TextQuoteAnchor search when the cssSelector misses, so it lands the highlight on the rendered prose. Sentence text is extracted by `core/domain/ReadaloudTextQuotes.kt` from the **Storyteller bundle** (`readaloudAudioRepository.bundleFile`, the EPUB that actually carries the spans — the ABS publisher EPUB may not, e.g. Project Hail Mary), keyed by **span id** (the track's refs are root-relative `OEBPS/xhtml/…` while the manifest is OPF-relative, so `href#id` keys mismatch; span ids are unique per book). Built once when the track loads.

Verify on device with a temporary `Log` in the decoration effect: a working highlight shows `mapSize>0, quoted=true, deco>=1`. `el=0` is fine (span stripped); `inBody=1` means the text is on the page.
