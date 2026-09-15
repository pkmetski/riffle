package com.riffle.core.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher

/**
 * Test [DispatcherProvider] — every dispatcher routes through the same [TestDispatcher] so a
 * `TestScope`'s scheduler controls all four. Pass `runTest`'s `testScheduler` via
 * `StandardTestDispatcher(testScheduler)` for full determinism.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestDispatcherProvider(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : DispatcherProvider {
    override val main: CoroutineDispatcher get() = dispatcher
    override val mainImmediate: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
}
