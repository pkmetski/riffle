# Footnote popup: preserve the marker number and make links clickable

## Problem

The footnote popup mangles two things in the note body:

1. **The marker number is destroyed.** Many EPUBs format a footnote entry as
   `[<a href="#ref">10</a>] KISSmetrics CEO…` — the brackets are plain text and
   the number is a back-reference anchor that links into the body. The resolver
   strips *all* back-reference anchors (`FootnoteResolver.noteText`), so the
   number disappears and the popup shows an orphaned `[] KISSmetrics CEO…`.

2. **Links are dead.** The resolver flattens the body with Jsoup `.text()`,
   which discards every `href`. Real `<a href="http…">` links lose their target,
   and bare-text URLs (e.g. `http://www.slideshare.net/hnshah/…`) are never
   linkified. The popup renders them as inert text.

Why the stripping existed: a back-reference marker is a dead link inside a popup
(it jumps to the body the popup already floats over), and some EPUBs point the
in-text superscript at the marker anchor itself, which used to yield a popup
showing *only* a number. Both concerns are real, but neither requires deleting
the digit — they only require not rendering it as a tappable link and not
landing on a bare-marker-only body.

## Decisions

- **Preserve the number in all footnotes**, rendered as plain (non-link) text.
  Both footnote shapes show their marker: `[10] KISSmetrics…` and `[1]
  Ostermalm…`. Trailing "return to text" glyphs (`↑`, `↩`, …) and non-numeric
  backlinks (`epub:type="backlink"` / `role="doc-backlink"` with text like
  "Return") are still removed — they carry no information.
- **Make both link forms clickable**: preserve real `<a href="http…">` /
  `mailto:` anchors (keeping their visible text), and linkify bare-text URLs.
  In-document `#fragment` anchors stay plain text (not navigable from a popup).

## Architecture

The resolver currently returns `String`, throwing away `href`s. Introduce a
plain-data model and thread it end-to-end; only the popup touches Compose.

```kotlin
data class FootnoteContent(val text: String, val links: List<FootnoteLink>)
data class FootnoteLink(val start: Int, val end: Int, val url: String) // end exclusive
```

- `FootnoteResolver` returns `FootnoteContent` (empty `links` = plain text). All
  marker/link/offset logic lives here — pure JVM, no Compose types.
- `AnchorTarget.Footnote`, `FootnotePopupState`, `EpubReaderViewModel.showFootnotePopup`,
  and the `onFootnoteTapped` callback carry `FootnoteContent` instead of `String`.
- `FootnotePopup` builds an `AnnotatedString`, adding one `LinkAnnotation.Url`
  span per `FootnoteLink`. `Text` renders them as styled, tappable links opened
  via the default `UriHandler` — no click wiring.

Rejected: building the `AnnotatedString` inside the resolver. Keeping the
resolver a pure data transformer keeps the offset/link logic fully unit-testable
with no Compose-runtime dependency; the popup conversion is ~8 lines.

## Resolver processing (`noteContent(block): FootnoteContent`)

On a clone of the note element:

1. **Classify and rewrite every `<a>`:**
   - External (`href` starts `http://`, `https://`, `mailto:`) → leave in place;
     becomes a link span in step 2.
   - In-document numeric marker (`href="#…"`, text matches the marker regex —
     `10`, `[1]`, `1.`) → **unwrap** (number survives as plain text, dead link
     gone). *Numeric check wins over the chrome check.*
   - In-document chrome (`href="#…"`, non-numeric, and a backlink glyph or
     `epub:type="backlink"` / `role="doc-backlink"`) → **remove**.
   - Any other in-document / relative anchor → **unwrap** to plain text.
2. **Build text + link spans via one in-order DOM walk** with whitespace
   collapsing (single spaces, no leading/trailing) so offsets are exact — no
   `.text()` + substring search. Each surviving external `<a>` records
   `FootnoteLink(start, end, href)` around its visible text.
3. **Linkify bare-text URLs** (`https?://…`) in the assembled string, trimming
   trailing prose punctuation (`. , ; : ) ] } > " '`), skipping ranges already
   covered by a step-2 anchor span.

Empty resulting text → `null` (unchanged "no popup" behavior).

### Entry points

- `extractFootnoteContent(doc, targetId)` — JS-bridge path (renamed from
  `extractFootnoteText`); still uses `getElementById` and climbs from a
  marker-anchor target to the enclosing note entry as today.
- `footnoteContent(noteHtml)` — Readium `shouldFollowInternalLink` fallback
  (`EpubReaderScreen.kt:854`), replacing its ad-hoc `Jsoup.parse(...).text()`
  so both paths behave identically.

## Popup rendering

```kotlin
val linkColor = MaterialTheme.colorScheme.primary
val annotated = buildAnnotatedString {
    append(state.content.text)
    state.content.links.forEach { l ->
        addLink(
            LinkAnnotation.Url(l.url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
            l.start, l.end,
        )
    }
}
Text(text = annotated, …)
```

## Testing

Pure-JVM `FootnoteResolverTest`:

- **Updated (intentional, per decision):** three cases that asserted the marker
  was stripped now expect it preserved — e.g. trailing-up-arrow body becomes
  `[1] Ostermalm is a district. Note.` (leading `[1]` kept, trailing `↑`
  removed). The `epub:type="backlink"` / text "Return" case still removes the
  anchor → unchanged.
- **New:** bare-URL linkify (offset + url asserted); `<a href>` anchor
  preservation with differing visible text; number-with-text-brackets
  (`[<a>10</a>]` → `[10] …`); offset correctness against the produced `text`;
  bare URL with trailing punctuation trimmed; URL range not double-linkified
  when already an anchor.

`EpubReaderViewModelFootnoteTest` and any other `FootnotePopupState("…")` /
string-based call sites update to the `FootnoteContent` type.

## Out of scope

- Rendering other inline formatting (bold/italic) inside the popup.
- Navigating in-document `#fragment` links from the popup.
