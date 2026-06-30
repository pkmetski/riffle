package com.riffle.app.testing

import com.riffle.core.domain.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Test [ApplicationScope] that runs on whatever [CoroutineScope] the test wires up — typically a
 * `TestScope` (`StandardTestDispatcher`) so launches are deterministic via `advanceUntilIdle()`.
 *
 * Mirrors `DefaultApplicationScope` in production: [withSurvivable] uses `async { }.await()` so the
 * work continues on the survivable scope even when the caller cancels.
 */
class TestApplicationScope(private val scope: CoroutineScope) : ApplicationScope {
    override val coroutineScope: CoroutineScope = scope

    override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(block = block)

    override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T =
        scope.async(block = block).await()
}
