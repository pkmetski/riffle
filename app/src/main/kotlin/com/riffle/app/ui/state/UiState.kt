package com.riffle.app.ui.state

/**
 * Unified screen state taxonomy — replaces the per-feature Loading/Ready/Error sealed classes that
 * each ViewModel used to define. Screens consume it via [UiStateScaffold] which renders a common
 * loader / error treatment; the `success` slot receives the domain object [T].
 *
 * Screens that need extra discriminators (e.g. a Downloading sub-state inside a player) parameterise
 * [T] with their own sealed type instead of nesting variants into this wrapper.
 */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>

    data class Success<T>(val value: T) : UiState<T>

    /**
     * An error worth showing the user. [retry] is optional — when non-null the scaffold shows a
     * retry affordance that invokes it. [code] is for downstream logging / analytics; the [message]
     * is the human-readable string the scaffold renders.
     */
    data class Error(
        val message: String,
        val code: String? = null,
        val retry: (() -> Unit)? = null,
    ) : UiState<Nothing>
}

inline fun <T, R> UiState<T>.map(transform: (T) -> R): UiState<R> = when (this) {
    UiState.Loading -> UiState.Loading
    is UiState.Success -> UiState.Success(transform(value))
    is UiState.Error -> this
}

fun <T> UiState<T>.valueOrNull(): T? = (this as? UiState.Success)?.value
