package com.riffle.feature.source.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_no_items_in_this_library
import org.jetbrains.compose.resources.stringResource

/** The empty state both hosts show when a library (or a browse facet) has nothing in it. */
@Composable
fun EmptyLibrary(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(Res.string.ui_no_items_in_this_library),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
