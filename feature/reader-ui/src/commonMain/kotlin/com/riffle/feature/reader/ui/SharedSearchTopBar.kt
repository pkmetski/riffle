package com.riffle.feature.reader.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import com.riffle.feature.designsystem.RiffleIcons
import com.riffle.feature.designsystem.TestTags
import org.jetbrains.compose.resources.stringResource
import com.riffle.feature.reader.ui.generated.resources.Res
import com.riffle.feature.reader.ui.generated.resources.ui_back
import com.riffle.feature.reader.ui.generated.resources.ui_close_search
import com.riffle.feature.reader.ui.generated.resources.ui_next_result
import com.riffle.feature.reader.ui.generated.resources.ui_no_results
import com.riffle.feature.reader.ui.generated.resources.ui_previous_result
import com.riffle.feature.reader.ui.generated.resources.ui_result_position
import com.riffle.feature.reader.ui.generated.resources.ui_search_in_book

/**
 * Reader search top bar — shared by Android and iOS. Shows the search query field, the
 * hit count ("n of N"), and prev/next navigation buttons. Matches the Android [SearchTopBar]
 * exactly and replaces the iOS inline [BasicTextField] search UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedSearchTopBar(
    query: String,
    resultCount: Int,
    currentIndex: Int,
    onQueryChange: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val noResultsLabel = stringResource(Res.string.ui_no_results)
    val resultPositionFormat = stringResource(Res.string.ui_result_position, currentIndex + 1, resultCount)
    val countText = when {
        query.length < 2 -> ""
        resultCount == 0 -> noResultsLabel
        else -> resultPositionFormat
    }

    TopAppBar(
        windowInsets = TopAppBarDefaults.windowInsets,
        colors = readerTopAppBarColors(),
        modifier = modifier,
        navigationIcon = {
            IconButton(
                onClick = onNavigateBack,
                modifier = Modifier.testTag(TestTags.READER_BACK),
            ) {
                Icon(RiffleIcons.ArrowBack, contentDescription = stringResource(Res.string.ui_back))
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        stringResource(Res.string.ui_search_in_book),
                        color = Color.White.copy(alpha = 0.6f),
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag(TestTags.READER_SEARCH_FIELD),
            )
        },
        actions = {
            Text(
                text = countText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .padding(end = 4.dp)
                    .testTag(TestTags.READER_SEARCH_COUNT),
            )
            IconButton(
                onClick = onPrev,
                enabled = currentIndex > 0,
                modifier = Modifier.testTag(TestTags.READER_SEARCH_PREV),
            ) {
                Icon(
                    RiffleIcons.KeyboardArrowUp,
                    contentDescription = stringResource(Res.string.ui_previous_result),
                )
            }
            IconButton(
                onClick = onNext,
                enabled = resultCount > 0 && currentIndex < resultCount - 1,
                modifier = Modifier.testTag(TestTags.READER_SEARCH_NEXT),
            ) {
                Icon(
                    RiffleIcons.KeyboardArrowDown,
                    contentDescription = stringResource(Res.string.ui_next_result),
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag(TestTags.READER_SEARCH_CLOSE),
            ) {
                Icon(
                    RiffleIcons.Close,
                    contentDescription = stringResource(Res.string.ui_close_search),
                )
            }
        },
    )
}
