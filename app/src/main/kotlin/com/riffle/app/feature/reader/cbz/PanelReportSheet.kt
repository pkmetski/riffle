package com.riffle.app.feature.reader.cbz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.comic.panel.PanelBinaryMask
import com.riffle.core.domain.comic.panel.PanelDetectionFailureType

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PanelReportSheet(
    viewModel: PanelReportViewModel,
    mask: PanelBinaryMask,
    maskBitmap: ImageBitmap,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Report panel detection issue",
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelDetectionFailureType.entries.forEach { ft ->
                    FilterChip(
                        selected = state.failureType == ft,
                        onClick = { viewModel.setFailureType(ft) },
                        label = { Text(ft.label) },
                    )
                }
            }

            var canvasSize by remember { mutableStateOf(IntSize.Zero) }
            val scaleX = if (canvasSize.width > 0 && mask.width > 0) canvasSize.width.toFloat() / mask.width else 1f
            val scaleY = if (canvasSize.height > 0 && mask.height > 0) canvasSize.height.toFloat() / mask.height else 1f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(mask.width.toFloat() / mask.height.toFloat())
                    .border(1.dp, Color.Gray)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(mask, canvasSize) {
                        detectTapGestures { offset ->
                            if (scaleX > 0f && scaleY > 0f) {
                                viewModel.onTap(
                                    tappedImageX = (offset.x / scaleX).toInt().coerceIn(0, mask.width - 1),
                                    tappedImageY = (offset.y / scaleY).toInt().coerceIn(0, mask.height - 1),
                                )
                            }
                        }
                    },
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawImage(maskBitmap)
                    viewModel.detectedPanels.forEachIndexed { i, p ->
                        val selected = state.tappedPanelIndex == i
                        drawRect(
                            color = if (selected) Color.Red else Color.Blue,
                            topLeft = Offset(p.x * scaleX, p.y * scaleY),
                            size = Size(p.width * scaleX, p.height * scaleY),
                            style = Stroke(width = if (selected) 3f else 1.5f),
                        )
                    }
                    val tx = state.tappedX
                    val ty = state.tappedY
                    if (tx != null && ty != null && state.tappedPanelIndex == null) {
                        drawCircle(
                            color = Color.Magenta,
                            radius = 10f,
                            center = Offset(tx * scaleX, ty * scaleY),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::setNotes,
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            if (state.error != null) {
                Text(state.error!!, color = Color.Red)
            }

            if (state.submittedIssueUrl != null) {
                Text("Filed: ${state.submittedIssueUrl}", color = Color(0xFF388E3C))
            }

            Button(
                onClick = onSubmit,
                enabled = !state.submitting && state.submittedIssueUrl == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.submitting) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else Text("Submit to GitHub")
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
