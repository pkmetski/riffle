package com.riffle.core.sync

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression: on the validated offline→online edge, [kickSweepsOnReconnect] must invoke both the
 * progress sweep and the annotation sweep, in that order (progress first so audiobook position
 * uploads land before the annotation write that a subsequent session may depend on). Edge
 * semantics themselves are pinned by
 * [com.riffle.core.domain.ConnectivityReconnectsTest]; this test pins the composition.
 *
 * Moved from `app/src/test` to `commonTest` with the kicker itself (#1071 §14) so the same
 * assertion runs on `iosSimulatorArm64Test` — iOS is the platform with no WorkManager backstop
 * behind it, so the reconnect edge matters more there than on Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectSyncKickerTest {

    @Test
    fun `runs progress then annotation sweep on reconnect`() = runTest {
        val isOnline = MutableStateFlow(false)
        val calls = mutableListOf<String>()

        val job = launch {
            kickSweepsOnReconnect(
                isOnline = isOnline,
                runProgressSweep = { calls += "progress" },
                runAnnotationSweep = { calls += "annotation" },
            )
        }
        runCurrent()

        isOnline.value = true
        runCurrent()

        assertEquals(listOf("progress", "annotation"), calls)
        job.cancel()
    }
}
