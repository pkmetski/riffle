package com.riffle.app.feature.reader

import org.json.JSONObject

/**
 * The richer figure payload posted by [FigureTapScript]'s long-press (500ms) listener — as opposed
 * to [FigureTapMessageParser]'s tap payload, this carries the resolved caption and (for `<svg>`
 * targets) the serialized markup, since long-press opens the annotate-figure flow rather than the
 * zoom overlay.
 *
 * [kind] is the lower-cased tag name (`"img"`, `"svg"`, `"picture"`). [href] is the resolved image
 * `src` for `img`/`picture` targets — null for `svg`. [svg] is the `outerHTML` for `svg` targets —
 * null otherwise. [elementId] is the target's `id` attribute, or null if unset.
 *
 * [rectX] / [rectY] / [rectW] / [rectH] are the long-pressed figure's `getBoundingClientRect()` in
 * CSS px, viewport-relative to the WebView that posted the payload — used to anchor
 * `HighlightActionsPopup` at the figure (Kotlin converts CSS px to device px via density, then to
 * screen coordinates). Default to `0` so a stale/old-format JS payload missing these fields doesn't
 * crash the parser; a zero rect just anchors the popup at the WebView's origin.
 */
internal data class FigureLongPressPayload(
    val kind: String,
    val caption: String,
    val href: String?,
    val svg: String?,
    val elementId: String?,
    val rectX: Int = 0,
    val rectY: Int = 0,
    val rectW: Int = 0,
    val rectH: Int = 0,
    /**
     * Data URI (`data:image/…;base64,…`) of the figure, rasterised from the WebView at long-press
     * time via a canvas. Persisted on the annotation so both the annotations panel row's thumbnail
     * and the Highlights-mode elided reader can display the figure without needing the source
     * Publication container to be reloaded. Null on cross-origin blocks or SVG capture failure.
     */
    val imageBytes: String? = null,
    /**
     * Non-null when the caption resolves to a real DOM element (`<figcaption>` or a nearby text-
     * prefix `<p>`/`<div>`) — i.e. a range we can turn into a proper text-highlight anchor. Null
     * when the caption came from `alt`/`aria-label` (invisible attribute, no range) or when no
     * caption resolved at all. When present, [EpubReaderViewModel.onFigureLongPress] persists the
     * figure as a `TYPE_HIGHLIGHT` covering the caption text with the figure as an
     * `embeddedFigure`, so the caption becomes a real annotated span (tap/select routes to the
     * same annotation; new highlights can't land on top of it). When null, the annotation falls
     * back to `TYPE_IMAGE` (no visible caption to anchor to).
     */
    val captionRange: CaptionRange? = null,
)

/**
 * A resolved caption's DOM text, plus a short text-context window on either side, captured on the
 * JS side at long-press time. Kotlin re-locates the caption in the chapter HTML via the same
 * `locateSnippetInBody`/`buildHighlightCfiRange` machinery a normal text selection uses; the
 * before/after context disambiguates when the same caption text ("Fig. 1") appears more than
 * once in the chapter.
 */
internal data class CaptionRange(
    val text: String,
    val textBefore: String,
    val textAfter: String,
)

/**
 * Parses the JSON payload posted by [FigureTapScript]'s long-press listener into a
 * [FigureLongPressPayload]. `org.json.JSONObject#optString` collapses a JSON `null` to `""`, which
 * would corrupt the kind/href/svg-branch distinction downstream (Task 6 uses `href == null` /
 * `svg == null` to tell an image target from an inline-SVG target) — so every optional field is
 * explicitly re-nulled via `takeIf { !obj.isNull(...) }` after the `optString` read.
 */
internal object FigureLongPressMessageParser {
    fun parse(json: String): FigureLongPressPayload {
        val obj = JSONObject(json)
        val captionRange = if (obj.has("captionRange") && !obj.isNull("captionRange")) {
            val cr = obj.getJSONObject("captionRange")
            val text = cr.optString("text", "")
            if (text.isNotBlank()) {
                CaptionRange(
                    text = text,
                    textBefore = cr.optString("textBefore", ""),
                    textAfter = cr.optString("textAfter", ""),
                )
            } else null
        } else null
        return FigureLongPressPayload(
            kind = obj.getString("kind"),
            caption = obj.optString("caption", ""),
            href = obj.optString("href").takeIf { !obj.isNull("href") && it.isNotEmpty() },
            svg = obj.optString("svg").takeIf { !obj.isNull("svg") && it.isNotEmpty() },
            elementId = obj.optString("elementId").takeIf { !obj.isNull("elementId") && it.isNotEmpty() },
            rectX = obj.optInt("rectX", 0),
            rectY = obj.optInt("rectY", 0),
            rectW = obj.optInt("rectW", 0),
            rectH = obj.optInt("rectH", 0),
            imageBytes = obj.optString("imageBytes").takeIf { !obj.isNull("imageBytes") && it.isNotEmpty() },
            captionRange = captionRange,
        )
    }
}
