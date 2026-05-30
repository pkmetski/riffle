package com.riffle.app.feature.reader

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Text that shrinks its font size to fit the available width/height,
 * falling back to ellipsis once it hits [minFontSize].
 */
@Composable
fun AutoResizeText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    minFontSize: TextUnit = 10.sp,
) {
    var resizedStyle by remember(text, style.fontSize, maxLines) { mutableStateOf(style) }
    var readyToDraw by remember(text, style.fontSize, maxLines) { mutableStateOf(false) }

    Text(
        text = text,
        style = resizedStyle,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.drawWithContent { if (readyToDraw) drawContent() },
        onTextLayout = { result ->
            if (result.hasVisualOverflow) {
                val next = resizedStyle.fontSize * 0.95f
                if (next.value >= minFontSize.value) {
                    resizedStyle = resizedStyle.copy(fontSize = next)
                } else {
                    resizedStyle = resizedStyle.copy(fontSize = minFontSize)
                    readyToDraw = true
                }
            } else {
                readyToDraw = true
            }
        },
    )
}
