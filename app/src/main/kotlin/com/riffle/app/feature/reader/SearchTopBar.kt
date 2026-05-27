package com.riffle.app.feature.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

object SearchTopBarTags {
    const val FIELD = "search_field"
    const val COUNT = "search_result_count"
    const val PREV = "search_prev"
    const val NEXT = "search_next"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    query: String,
    resultCount: Int,
    currentIndex: Int,
    onQueryChange: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val countText = when {
        query.length < 2 -> ""
        resultCount == 0 -> "No results"
        else -> "${currentIndex + 1} of $resultCount"
    }

    TopAppBar(
        windowInsets = TopAppBarDefaults.windowInsets,
        navigationIcon = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Search in book…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag(SearchTopBarTags.FIELD),
            )
        },
        actions = {
            Text(
                text = countText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .testTag(SearchTopBarTags.COUNT),
            )
            IconButton(
                onClick = onPrev,
                enabled = currentIndex > 0,
                modifier = Modifier.testTag(SearchTopBarTags.PREV),
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous result")
            }
            IconButton(
                onClick = onNext,
                enabled = currentIndex < resultCount - 1,
                modifier = Modifier.testTag(SearchTopBarTags.NEXT),
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next result")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close search")
            }
        },
    )
}
