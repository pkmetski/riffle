package com.riffle.feature.reader

/**
 * Cadence's start-position resolver, extracted from `EpubReaderViewModel.onCadencePageTopResolved`
 * for unit-testing without a WebView.
 *
 *  - Happy path: the WebView probe returned `"chapter#cd-N"` (full ref, when the resolver JS
 *    could read the chapter attribute the tokeniser stamped onto <html>) OR a bare `"cd-N"` (old
 *    payload / DOM never tokenised). If it's a full ref we use it directly — bypassing the
 *    Readium-locator-href lag that used to file cd-N under the previous chapter's href.
 *  - Bare id + [href] combined: fall back to the old behaviour, `"$href#$probed"`. Only correct
 *    when Readium's locator href actually matches the currently-rendered DOM, which is not
 *    guaranteed after a paginated chapter turn — but it's the best we can do without the JS
 *    chapter hint.
 *  - Probe failure ([probedFragmentId] null/blank): fall back to the FIRST fragment in
 *    [chapterHrefs] whose value equals [href] — i.e. the first tokenised sentence of the
 *    chapter the user is currently on. The naive alternative — dispatching Start with no
 *    goTo — makes [com.riffle.core.domain.sentence.WpmTicker.play] index into position 0 of
 *    the merged cross-chapter fragment list, which is the first sentence of the FIRST chapter
 *    tokenised this session (often several pages back). That produces the "Cadence starts on a
 *    previous page and Readium scrolls me back" bug.
 *  - Chapter not tokenised yet (rare — `startCadence` gates on a known locator): returns null
 *    so the caller still dispatches Start; the ticker's own default kicks in as a last resort.
 *
 * [knownRefs] is the set of refs the ticker will accept (i.e. `_cadenceQuotes.keys`). When
 * provided, the resolved ref is validated against it — a ref that isn't in the set is rejected
 * (returned as null) rather than passed to a `WpmTicker.goTo` that would silently no-op and
 * cause play() to fall to `orderedFragments[0]`. Old callers that don't know the map can pass
 * `null` to skip the check.
 */
fun resolveCadenceStartRef(
    href: String,
    probedFragmentId: String?,
    chapterHrefs: Map<String, String>,
    knownRefs: Set<String>? = null,
): String? {
    val probed = probedFragmentId?.takeIf { it.isNotBlank() }
    val candidate = when {
        probed == null -> chapterHrefs.entries.firstOrNull { it.value == href }?.key
        probed.contains('#') -> probed
        else -> "$href#$probed"
    } ?: return null
    if (knownRefs != null && candidate !in knownRefs) {
        // Reject a stale/mislabeled ref rather than let the ticker fall to position 0.
        // Prefer the CHAPTER carried in [probed] when it's a full ref (JS-provided, DOM-
        // authoritative) over the Kotlin-supplied [href] — the whole reason knownRefs might
        // reject a candidate is that Readium's locator href lagged the tokeniser by one
        // chapter, and re-querying by that stale href would re-introduce the very lag this
        // resolver was written to guard against.
        val fallbackHref = probed?.takeIf { it.contains('#') }?.substringBefore('#') ?: href
        return chapterHrefs.entries.firstOrNull { it.value == fallbackHref }?.key
            ?.takeIf { it in knownRefs }
    }
    return candidate
}
