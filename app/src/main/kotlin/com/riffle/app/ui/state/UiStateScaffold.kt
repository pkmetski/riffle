package com.riffle.app.ui.state

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Renders the conventional Loading / Error treatment for a [UiState], delegating the [content] slot
 * to the success case. Use this in every screen that exposes a [UiState] so spinners, error
 * messages, and retry affordances stay consistent across the app.
 *
 * The Error path uses [UiState.Error.retry] when present; an explicit [onRetry] parameter wins if
 * the caller wants to override (e.g. retry from a screen-local lambda not stored in state).
 */
@Composable
fun <T> UiStateScaffold(
    state: UiState<T>,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    loading: @Composable () -> Unit = { DefaultLoading() },
    error: @Composable (UiState.Error, (() -> Unit)?) -> Unit = { e, retry -> DefaultError(e, retry) },
    content: @Composable (T) -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            UiState.Loading -> loading()
            is UiState.Error -> error(state, onRetry ?: state.retry)
            is UiState.Success -> content(state.value)
        }
    }
}

@Composable
private fun DefaultLoading() {
    CircularProgressIndicator()
}

@Composable
private fun DefaultError(
    error: UiState.Error,
    onRetry: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = error.message,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}
