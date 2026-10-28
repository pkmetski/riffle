package com.riffle.app.feature.reader

import android.os.Build
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class ImmersiveModeStateTest {

    @Test
    fun `all API levels use sticky IMMERSIVE so bars auto-hide after any transient reveal`() {
        listOf(
            Build.VERSION_CODES.N_MR1,
            Build.VERSION_CODES.Q,
            Build.VERSION_CODES.R,
            Build.VERSION_CODES.TIRAMISU,
        ).forEach { sdk ->
            assertEquals(
                "sticky expected at API $sdk",
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                immersiveSystemBarsBehavior(sdk),
            )
        }
    }
}
