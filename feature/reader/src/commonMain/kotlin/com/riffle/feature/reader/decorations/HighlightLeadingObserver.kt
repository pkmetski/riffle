package com.riffle.feature.reader.decorations

const val HIGHLIGHT_TINT_CLASS = "riffle-highlight-tint"

private val HIGHLIGHT_LEADING_ADJUSTMENT_HELPER_JS: String = """
    (function() {
      var lineHeightOf = function(element) {
        var target = element || document.body || document.documentElement;
        var style = window.getComputedStyle(target);
        var lineHeight = parseFloat(style.lineHeight);
        if (!isFinite(lineHeight)) {
          var fontSize = parseFloat(style.fontSize);
          lineHeight = (isFinite(fontSize) ? fontSize : 16) * 1.2;
        }
        return lineHeight;
      };

      window.__riffleFillInlineHighlightLeading = function(mark) {
        if (!mark) return;
        mark.style.removeProperty('padding-top');
        mark.style.removeProperty('padding-bottom');
        var rects = mark.getClientRects();
        if (!rects || rects.length === 0) return;
        var leading = Math.max(0, lineHeightOf(mark) - rects[0].height);
        var half = leading / 2;
        mark.style.setProperty('padding-top', half + 'px', 'important');
        mark.style.setProperty('padding-bottom', half + 'px', 'important');
        mark.style.setProperty('-webkit-box-decoration-break', 'clone');
        mark.style.setProperty('box-decoration-break', 'clone');
      };

      window.__riffleFillReadiumHighlightLeading = function() {
        var boxes = document.querySelectorAll('.$HIGHLIGHT_TINT_CLASS');
        Array.prototype.forEach.call(boxes, function(box) {
          var baseTop = parseFloat(box.getAttribute('data-riffle-base-top'));
          var baseHeight = parseFloat(box.getAttribute('data-riffle-base-height'));
          if (!isFinite(baseTop) || !isFinite(baseHeight)) {
            baseTop = parseFloat(box.style.top);
            baseHeight = parseFloat(box.style.height);
            if (!isFinite(baseTop) || !isFinite(baseHeight)) return;
            box.setAttribute('data-riffle-base-top', baseTop);
            box.setAttribute('data-riffle-base-height', baseHeight);
          }

          box.style.top = baseTop + 'px';
          box.style.height = baseHeight + 'px';
          var rect = box.getBoundingClientRect();
          var source = null;
          var x = rect.left + Math.min(Math.max(rect.width / 2, 1), Math.max(rect.width - 1, 1));
          var y = rect.top + rect.height / 2;
          if (x >= 0 && x < window.innerWidth && y >= 0 && y < window.innerHeight) {
            source = document.elementFromPoint(x, y);
          }
          var mediaSelector = 'img,svg,picture,canvas,video,object,embed,iframe';
          var intersectsMedia = Array.prototype.some.call(document.querySelectorAll(mediaSelector), function(media) {
            var mediaRect = media.getBoundingClientRect();
            return rect.left < mediaRect.right && rect.right > mediaRect.left &&
              rect.top < mediaRect.bottom && rect.bottom > mediaRect.top;
          });
          if (intersectsMedia || (source && source.closest && source.closest(mediaSelector))) return;
          var lineHeight = lineHeightOf(source);
          if (lineHeight <= baseHeight && box.parentNode) {
            var nearestAdvance = Infinity;
            var siblings = box.parentNode.querySelectorAll('.$HIGHLIGHT_TINT_CLASS');
            Array.prototype.forEach.call(siblings, function(sibling) {
              if (sibling === box) return;
              var siblingTop = parseFloat(sibling.getAttribute('data-riffle-base-top'));
              if (!isFinite(siblingTop)) siblingTop = parseFloat(sibling.style.top);
              var advance = Math.abs(siblingTop - baseTop);
              if (advance > 0.5 && advance <= baseHeight * 1.5 && advance < nearestAdvance) {
                nearestAdvance = advance;
              }
            });
            if (isFinite(nearestAdvance)) lineHeight = nearestAdvance;
          }
          var leading = Math.max(0, lineHeight - baseHeight);
          var half = leading / 2;
          box.style.top = (baseTop - half) + 'px';
          box.style.height = (baseHeight + leading) + 'px';
        });
        return boxes.length;
      };
    })();
""".trimIndent()

/**
 * Installs a MutationObserver that expands every `.riffle-highlight-tint` element to full
 * line-height the moment it is added to the DOM — as a microtask, before the browser paints.
 * Replaces the old requestAnimationFrame approach which fired after first paint, causing a
 * visible flash of thin decoration boxes on every Readium requestLayout cycle.
 */
fun installHighlightLeadingObserverJs(): String = """
    $HIGHLIGHT_LEADING_ADJUSTMENT_HELPER_JS
    (function() {
      if (window.__riffleLeadingObserver) return;
      window.__riffleLeadingObserver = new MutationObserver(function(mutations) {
        var hasHighlightAdd = false;
        for (var mi = 0; mi < mutations.length; mi++) {
          var added = mutations[mi].addedNodes;
          for (var ni = 0; ni < added.length; ni++) {
            var node = added[ni];
            if (node.nodeType !== 1) continue;
            if (node.classList.contains('$HIGHLIGHT_TINT_CLASS') ||
                node.querySelector && node.querySelector('.$HIGHLIGHT_TINT_CLASS')) {
              hasHighlightAdd = true;
              break;
            }
          }
          if (hasHighlightAdd) break;
        }
        if (hasHighlightAdd) window.__riffleFillReadiumHighlightLeading();
      });
      window.__riffleLeadingObserver.observe(
        document.body || document.documentElement,
        { childList: true, subtree: true }
      );
    })();
""".trimIndent()
