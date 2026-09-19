package com.riffle.core.domain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The single [ApplicationScope] implementation for both platforms.
 *
 * [withSurvivable] uses `async { ... }.await()` on the backing scope so the actual work runs on
 * the survivable scope (and completes even if the caller cancels mid-await), while only the await
 * itself is cancellation-aware in the caller. iOS previously bound a stub whose withSurvivable
 * ran the block inline in the caller's context, so terminal writes (progress flush on book
 * close, ADR 0039) were cancelled along with the ViewModel — the exact failure this class exists
 * to prevent.
 */
class DefaultApplicationScope constructor(
    private val scope: CoroutineScope,
) : ApplicationScope {

    override val coroutineScope: CoroutineScope = scope

    override fun launchSurvivable(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(block = block)

    override suspend fun <T> withSurvivable(block: suspend CoroutineScope.() -> T): T =
        scope.async(block = block).await()
}
