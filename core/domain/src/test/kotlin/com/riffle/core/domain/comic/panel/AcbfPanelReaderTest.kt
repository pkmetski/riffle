package com.riffle.core.domain.comic.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcbfPanelReaderTest {

    private val reader = AcbfPanelReader()

    @Test
    fun `two pages with frames each yield PagePanels in page order`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ACBF>
              <body>
                <page image="p1.jpg">
                  <frame points="20,20 190,20 190,270 20,270"/>
                  <frame points="210,20 380,20 380,270 210,270"/>
                </page>
                <page image="p2.jpg">
                  <frame points="20,20 380,20 380,270 20,270"/>
                </page>
              </body>
            </ACBF>
        """.trimIndent()

        val result = reader.read(xml, listOf(400 to 560, 400 to 560))

        assertEquals(2, result.size)
        assertEquals(0, result[0].pageIndex)
        assertEquals(PanelSource.Acbf, result[0].source)
        assertEquals(2, result[0].panels.size)
        assertEquals(1, result[1].pageIndex)
        assertEquals(1, result[1].panels.size)
    }

    @Test
    fun `polygon points collapse to axis-aligned bounding box`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ACBF>
              <body>
                <page image="p1.jpg">
                  <frame points="30,50 200,40 210,300 20,280"/>
                </page>
              </body>
            </ACBF>
        """.trimIndent()

        val result = reader.read(xml, listOf(400 to 560))

        assertEquals(1, result.size)
        val p = result[0].panels[0]
        assertEquals(20, p.x)   // min x across corners
        assertEquals(40, p.y)   // min y
        assertEquals(210 - 20, p.width)
        assertEquals(300 - 40, p.height)
    }

    @Test
    fun `pages with no frames are omitted from the result`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ACBF>
              <body>
                <page image="p1.jpg"/>
                <page image="p2.jpg">
                  <frame points="20,20 380,20 380,540 20,540"/>
                </page>
              </body>
            </ACBF>
        """.trimIndent()

        val result = reader.read(xml, listOf(400 to 560, 400 to 560))

        assertEquals(1, result.size)
        assertEquals(1, result[0].pageIndex)
    }

    @Test
    fun `malformed points strings are skipped without failing the whole page`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ACBF>
              <body>
                <page image="p1.jpg">
                  <frame points="garbage,here"/>
                  <frame points="20,20 380,20 380,540 20,540"/>
                </page>
              </body>
            </ACBF>
        """.trimIndent()

        val result = reader.read(xml, listOf(400 to 560))

        assertEquals(1, result.size)
        assertEquals(1, result[0].panels.size)
    }

    @Test
    fun `blank input produces empty result`() {
        assertTrue(reader.read("", listOf(400 to 560)).isEmpty())
        assertTrue(reader.read("   ", listOf(400 to 560)).isEmpty())
    }
}
