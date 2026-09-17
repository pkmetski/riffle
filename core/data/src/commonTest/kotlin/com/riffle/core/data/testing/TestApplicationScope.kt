package com.riffle.core.data.testing

import com.riffle.core.domain.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalCoroutinesApi::class)
class TestApplicationScope(
    private val scope: CoroutineScope = CoroutineScope(UnconfinedTestDispatcher()),
) : ApplicationScope {
    override val coroutineScope: CoroutineScope = scope

    override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(block = block)

    override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T =
        scope.async(block = block).await()
}
