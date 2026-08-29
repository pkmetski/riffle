package com.riffle.app.feature.reader.cbz

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A null bitmap (streaming-phase fetch or decode still in flight) must render a loading
 * indicator. The previous behaviour fed the null straight into Coil, which resolves a null
 * model as an instant empty error — the user saw a fully blank page for the whole multi-second
 * streaming fetch.
 */
class CbzPageContentTest {

    @Test fun `null bitmap renders the loading indicator, not a blank page`() {
        assertEquals(CbzPageContent.Loading, cbzPageContent(null))
    }
}
