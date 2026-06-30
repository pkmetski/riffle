package com.riffle.app.di

import com.riffle.core.domain.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Survivable backing scope is injected via [@ApplicationCoroutineScope] so tests can supply a
 * `TestScope`-backed scope; production binds [Dispatchers.IO] + a [SupervisorJob] in `AppModule`.
 *
 * [withSurvivable] uses `async { ... }.await()` on the backing scope so the actual work runs on
 * the survivable scope (and completes even if the caller cancels mid-await), while only the await
 * itself is cancellation-aware in the caller.
 */
@Singleton
class DefaultApplicationScope @Inject constructor(
    @ApplicationCoroutineScope private val scope: CoroutineScope,
) : ApplicationScope {

    override val coroutineScope: CoroutineScope = scope

    override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(block = block)

    override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T =
        scope.async(block = block).await()
}
