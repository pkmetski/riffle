package com.riffle.feature.reader

/**
 * CSS injected on every page load to prevent tables from overflowing the page width.
 *
 * Publisher EPUBs (e.g. O'Reilly's "AI Engineering") often contain tables with explicit
 * pixel widths — via HTML `width` attributes, inline CSS, or `<col>` widths — that exceed
 * the reader's page content area.
 *
 * Two-part fix:
 *  1. `width: 100% !important` forces the TABLE element itself to the container width,
 *     overriding any explicit pixel width the publisher set.
 *  2. `table-layout: fixed !important` switches off the browser's automatic column-width
 *     algorithm (which respects minimum content widths and can force the table wider than
 *     its declared width). In fixed mode the browser distributes column space from the
 *     `<col>` / first-row cell widths as proportional fractions of the total 100% — if no
 *     explicit column widths are set, columns share the space evenly. This is the mechanism
 *     that actually prevents overflow; `width: 100%` alone does not prevent the table from
 *     exceeding its container under `table-layout: auto`.
 *
 * `overflow-wrap: break-word` on cells ensures that any remaining long unbreakable strings
 * (code, URLs) wrap rather than forcing the cells wider.
 */
object TableFit {

    val CSS: String = """
        table {
          width: 100% !important;
          table-layout: fixed !important;
        }
        td, th {
          overflow-wrap: break-word;
          word-break: break-word;
          min-width: 0 !important;
        }
    """.trimIndent()

    val INSTALL_SCRIPT: String = run {
        val css = CSS
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\\$")
        """
            (function() {
              if (!document.head) return;
              var id = 'riffle-table-fit';
              if (document.getElementById(id)) return;
              var style = document.createElement('style');
              style.id = id;
              style.textContent = `$css`;
              document.head.appendChild(style);
            })();
        """.trimIndent()
    }
}
