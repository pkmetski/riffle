package com.riffle.app.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapDownsampleTest {

    @Test fun `no downsample when source fits`() {
        assertEquals(1, calculateBitmapSampleSize(1000, 800, 2048, 2048))
        assertEquals(1, calculateBitmapSampleSize(2048, 2048, 2048, 2048))
    }

    @Test fun `power-of-two shrink when source exceeds target`() {
        // A 4000x3000 image into a 1024x1024 target: 4000/2=2000, 3000/2=1500;
        // both >= 1024 → sample=2. Then 2000/2=1000, 1500/2=750; both < 1024 → stop.
        assertEquals(2, calculateBitmapSampleSize(4000, 3000, 1024, 1024))
    }

    @Test fun `aggressive shrink for a very large image`() {
        // 8000x6000 into 1024x1024 → 4000,3000 (>=1024)→2, 2000,1500 (>=1024)→4,
        // 1000,750 (750<1024) stop.
        assertEquals(4, calculateBitmapSampleSize(8000, 6000, 1024, 1024))
    }

    @Test fun `zero or negative inputs return 1 as a defensive floor`() {
        assertEquals(1, calculateBitmapSampleSize(0, 800, 1024, 1024))
        assertEquals(1, calculateBitmapSampleSize(800, 0, 1024, 1024))
        assertEquals(1, calculateBitmapSampleSize(800, 800, 0, 1024))
        assertEquals(1, calculateBitmapSampleSize(800, 800, 1024, 0))
        assertEquals(1, calculateBitmapSampleSize(-1, -1, 1024, 1024))
    }
}
