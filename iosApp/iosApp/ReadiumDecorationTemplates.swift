import UIKit
import ReadiumNavigator

/// The HTML templates Readium renders Riffle's decorations with.
///
/// Two reasons this exists rather than `HTMLDecorationTemplate.defaultTemplates()`:
///
/// 1. **The default highlight template throws the tint's alpha away.** It renders
///    `tint.cssValue(alpha: alpha)` with the template-level `alpha` (0.3 by default), and
///    `cssValue(alpha:)` substitutes that for the colour's own alpha channel. Every Riffle
///    highlight would paint at 0.3 while Android paints the 0x80 (≈0.502) baked into
///    `HighlightColor.argb`, and the same book would look different on the two devices. These
///    templates read the tint's own alpha, so the palette stays the single source of truth.
/// 2. **The default set has only `highlight` and `underline`.** A noted highlight needs a margin
///    glyph and a strike-through emphasis needs a line through the text; Readium drops any
///    decoration whose style id has no registered template, so without these the note glyph and
///    the strike would silently render nothing.
///
/// The CSS mirrors Android's `NoteGlyphDecoration.kt` / `HighlightDecoration.kt` class names and
/// geometry so the two platforms paint the same marks.
enum RiffleDecorationTemplates {

    /// ADR 0056 strike-through. Its own style id because Readium has no built-in one.
    static let strikeStyleId: Decoration.Style.Id = "riffleStrike"
    /// Margin glyph marking a highlight that carries a note.
    static let noteGlyphStyleId: Decoration.Style.Id = "riffleNoteGlyph"
    /// A bar in the left gutter marking a bookmarked paragraph.
    static let sidemarkStyleId: Decoration.Style.Id = "riffleSidemark"

    private static let noteGlyphClass = "riffle-note-glyph"
    private static let noteGlyphIconClass = "riffle-note-glyph-icon"
    private static let strikeClass = "riffle-emphasis-strike"
    private static let sidemarkClass = "riffle-sidemark"

    /// SVG path from `Icons.Outlined.NoteAlt` (Apache 2.0), percent-encoded so it can sit in a
    /// CSS `url()` with no base64 step. Identical to Android's `NOTE_GLYPH_SVG_DATA_URI`.
    private static let noteGlyphDataUri =
        "data:image/svg+xml,"
        + "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E"
        + "%3Cpath d='M22,10l-6,-6H4C2.9,4,2,4.9,2,6v12c0,1.1,0.9,2,2,2h16c1.1,0,2,-0.9,2,-2V10Z"
        + "M16,4l4,4h-4V4ZM13,18H7v-2h6V18ZM17,14H7v-2h10V14ZM17,10H7V8h10V10Z'/%3E"
        + "%3C/svg%3E"

    static func all() -> [Decoration.Style.Id: HTMLDecorationTemplate] {
        [
            .highlight: highlight(),
            .underline: underline(),
            strikeStyleId: strike(),
            noteGlyphStyleId: noteGlyph(),
            sidemarkStyleId: sidemark(),
        ]
    }

    /// `tint.cssValue()` with no alpha override — the colour already carries the palette's alpha.
    private static func highlight() -> HTMLDecorationTemplate {
        HTMLDecorationTemplate(
            layout: .boxes,
            width: .wrap,
            element: { decoration in
                let tint = tintOf(decoration) ?? .yellow
                return "<div style=\"background-color: \(tint.cssValue()) !important; "
                    + "border-radius: 3px; box-sizing: border-box;\"/>"
            }
        )
    }

    private static func underline() -> HTMLDecorationTemplate {
        HTMLDecorationTemplate(
            layout: .boxes,
            width: .wrap,
            element: { decoration in
                let tint = tintOf(decoration) ?? .label
                return "<div style=\"box-sizing: border-box; border-bottom: 2px solid "
                    + "\(tint.cssValue());\"/>"
            }
        )
    }

    /// One div per line box with an `::after` rule at 50% height, which lands on the text's
    /// x-height and reads as a real strikethrough. Mirrors Android's `emphasisStrikeTemplate`.
    private static func strike() -> HTMLDecorationTemplate {
        HTMLDecorationTemplate(
            layout: .boxes,
            width: .wrap,
            element: { decoration in
                let tint = tintOf(decoration) ?? .label
                return "<div class=\"\(strikeClass)\" style=\"--riffle-strike-color: "
                    + "\(tint.cssValue());\"/>"
            },
            stylesheet: """
            .\(strikeClass) { position: absolute; pointer-events: none; }
            .\(strikeClass)::after {
                content: "";
                position: absolute;
                left: 0;
                right: 0;
                top: 50%;
                height: 0;
                border-top: 2px solid var(--riffle-strike-color);
            }
            """
        )
    }

    /// A transparent bounds div over the range with an absolutely-positioned icon child sitting
    /// in the left gutter. A real DOM child rather than `::before` so taps bubble to Readium's
    /// decoration listener, and `data-activable="1"` so Readium hit-tests the *icon*'s rect
    /// rather than the (much larger, text-covering) bounds div.
    private static func noteGlyph() -> HTMLDecorationTemplate {
        HTMLDecorationTemplate(
            layout: .bounds,
            width: .wrap,
            element: "<div class=\"\(noteGlyphClass)\">"
                + "<div class=\"\(noteGlyphIconClass)\" data-activable=\"1\"></div></div>",
            stylesheet: """
            .\(noteGlyphClass) { background: none; overflow: visible; position: relative; }
            .\(noteGlyphIconClass) {
                position: absolute;
                left: -28px;
                top: 2px;
                width: 28px;
                height: 28px;
                -webkit-mask-image: url("\(noteGlyphDataUri)");
                -webkit-mask-size: contain;
                -webkit-mask-repeat: no-repeat;
                background-color: currentColor;
                opacity: 0.40;
            }
            """
        )
    }

    /// A 3px bar hugging the left edge of the bookmarked block. Unlike the blue wash it replaced,
    /// it does not sit on top of the text, and because the bookmark locator carries
    /// `locations.fragments` (the captured `fragmentAnchor`) it lands on the paragraph the user
    /// actually bookmarked rather than on whatever the page-level progression resolved to.
    private static func sidemark() -> HTMLDecorationTemplate {
        HTMLDecorationTemplate(
            layout: .bounds,
            width: .wrap,
            element: { decoration in
                let tint = tintOf(decoration) ?? .systemBlue
                return "<div class=\"\(sidemarkClass)\" style=\"--riffle-sidemark-color: "
                    + "\(tint.cssValue());\"/>"
            },
            stylesheet: """
            .\(sidemarkClass) { background: none; overflow: visible; position: relative; }
            .\(sidemarkClass)::before {
                content: "";
                position: absolute;
                left: -10px;
                top: 0;
                bottom: 0;
                width: 3px;
                border-radius: 2px;
                background-color: var(--riffle-sidemark-color);
            }
            """
        )
    }

    private static func tintOf(_ decoration: Decoration) -> UIColor? {
        (decoration.style.config as? Decoration.Style.HighlightConfig)?.tint
    }

    /// Keeps each margin glyph inside the left edge of its paginated column.
    ///
    /// Port of Android's `noteGlyphViewportClampAfterApplyJs`. The glyph is drawn 28px left of
    /// its text; where the page margin has no room for that it would be laid out in the previous
    /// column and the reader would see a note marker with no note next to it. Readium publishes
    /// decoration DOM on its own animation frame, so this retries for a bounded number of frames
    /// rather than assuming the elements are already there.
    static func noteGlyphClampJs() -> String {
        """
        (function(){
          var frames = 0, seen = 0;
          function clamp(){
            try {
              var se = document.scrollingElement || document.documentElement;
              var iw = window.innerWidth;
              if (!se || iw <= 0) return;
              var icons = document.querySelectorAll('.\(noteGlyphIconClass)');
              for (var i = 0; i < icons.length; i++) {
                var icon = icons[i];
                var bounds = icon.parentElement;
                if (!bounds) continue;
                icon.style.webkitTransform = '';
                icon.style.transform = '';
                var bRect = bounds.getBoundingClientRect();
                var iRect = icon.getBoundingClientRect();
                var spreadLeft = Math.floor(Math.max(0, bRect.left + se.scrollLeft) / iw) * iw;
                var shift = Math.max(0, spreadLeft + 12 - (iRect.left + se.scrollLeft));
                if (shift > 0) {
                  icon.style.webkitTransform = 'translateX(' + shift + 'px)';
                  icon.style.transform = 'translateX(' + shift + 'px)';
                }
              }
              if (icons.length > 0) seen++;
            } catch (e) {}
            if (seen < 4 && frames++ < 72) requestAnimationFrame(clamp);
          }
          clamp();
        })()
        """
    }
}
