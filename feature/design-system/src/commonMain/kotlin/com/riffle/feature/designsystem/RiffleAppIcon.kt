package com.riffle.feature.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.riffle.feature.designsystem.generated.resources.Res
import com.riffle.feature.designsystem.generated.resources.ic_riffle_logo
import org.jetbrains.compose.resources.painterResource

@Composable
fun RiffleAppIcon(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    Image(
        painter = painterResource(Res.drawable.ic_riffle_logo),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(CircleShape),
    )
}
