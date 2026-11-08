package com.riffle.app.ui.state

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>

    data class Success<T>(val value: T) : UiState<T>

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
