package com.riffle.shared.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

private val TitleStyle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)
private val AuthorStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF666666))
private val ButtonTextStyle = TextStyle(
    fontWeight = FontWeight.SemiBold,
    fontSize = 16.sp,
    color = Color.White,
)

@Composable
fun LibraryItemDetailScreen(
    itemId: String,
    sourceId: String?,
    onBack: () -> Unit,
    onReadNotSupported: () -> Unit,
) {
    val vm: LibraryItemDetailViewModel = koinInject(parameters = { parametersOf(itemId, sourceId) })
    val uiState by vm.uiState.collectAsState()

    when (val state = uiState) {
        LibraryItemDetailUiState.Loading -> LoadingContent()
        LibraryItemDetailUiState.Error -> ErrorContent(onBack = onBack)
        is LibraryItemDetailUiState.Ready -> ReadyContent(
            state = state,
            onBack = onBack,
            onRead = onReadNotSupported,
            onToggleToRead = { vm.toggleToRead() },
        )
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(text = "Loading…", style = TextStyle(fontSize = 16.sp, color = Color(0xFF888888)))
    }
}

@Composable
private fun ErrorContent(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = "Item not found",
                style = TextStyle(fontSize = 16.sp, color = Color(0xFF888888)),
            )
            Spacer(modifier = Modifier.height(16.dp))
            BasicText(
                text = "← Back",
                style = TextStyle(
                    fontSize = 16.sp,
                    color = Color(0xFF6650A4),
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.clickable(onClick = onBack),
            )
        }
    }
}

@Composable
private fun ReadyContent(
    state: LibraryItemDetailUiState.Ready,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onToggleToRead: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "← Back",
                style = TextStyle(
                    fontSize = 16.sp,
                    color = Color(0xFF6650A4),
                    fontWeight = FontWeight.SemiBold,
                ),
                modifier = Modifier.clickable(onClick = onBack),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .width(160.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .align(Alignment.CenterHorizontally),
        ) {
            DefaultCoverPlaceholder(isAudiobook = state.item.hasAudio)
        }

        Spacer(modifier = Modifier.height(24.dp))

        BasicText(
            text = state.item.title,
            style = TitleStyle,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        BasicText(
            text = state.item.author,
            style = AuthorStyle,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(32.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF6650A4))
                    .clickable(onClick = onRead),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(text = "Read", style = ButtonTextStyle)
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (state.isInToRead) Color(0xFF6650A4) else Color(0xFFEAE0F8))
                    .clickable(onClick = onToggleToRead),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = if (state.isInToRead) "In To-Read" else "Add to To-Read",
                    style = ButtonTextStyle.copy(
                        color = if (state.isInToRead) Color.White else Color(0xFF6650A4),
                    ),
                )
            }
        }

        if (state.isOffline) {
            Spacer(modifier = Modifier.height(12.dp))
            BasicText(
                text = "You are offline",
                style = TextStyle(fontSize = 13.sp, color = Color(0xFFAA8800)),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
