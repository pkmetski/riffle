package com.riffle.app.feature.reader.cbz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.riffle.core.domain.comic.panel.PanelBinaryMask
import com.riffle.core.domain.comic.panel.PanelDetectionFailureType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PanelReportSheet(
    viewModel: PanelReportViewModel,
    mask: PanelBinaryMask,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val drawMode = state.failureType == PanelDetectionFailureType.MissedPanel ||
        state.failureType == PanelDetectionFailureType.MergedPanels

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

            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = state.failureType?.label ?: "Select issue type",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    PanelDetectionFailureType.entries.forEach { ft ->
                        DropdownMenuItem(
                            text = { Text(ft.label) },
                            onClick = { viewModel.setFailureType(ft); expanded = false },
                        )
                    }
                }
            }

            // Black-on-white — same palette as the PNG uploaded to GitHub (ADR 0062).
            val maskBitmap = remember(mask) {
                val pixels = IntArray(mask.width * mask.height) { i ->
                    if (mask.data[i] == 1.toByte()) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                Bitmap.createBitmap(pixels, mask.width, mask.height, Bitmap.Config.ARGB_8888)
                    .asImageBitmap()
            }

            var canvasSize by remember { mutableStateOf(IntSize.Zero) }
            val scaleX = if (canvasSize.width > 0 && mask.width > 0) canvasSize.width.toFloat() / mask.width else 1f
            val scaleY = if (canvasSize.height > 0 && mask.height > 0) canvasSize.height.toFloat() / mask.height else 1f

            // In-progress draw state (canvas coords, local only)
            var dragStart by remember { mutableStateOf<Offset?>(null) }
            var dragCurrent by remember { mutableStateOf<Offset?>(null) }

            if (drawMode) {
                val hint = if (state.failureType == PanelDetectionFailureType.MissedPanel)
                    "Draw expected panel rectangles" else "Draw panel boundary lines"
                Text(hint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(mask.width.toFloat() / mask.height.toFloat())
                    .border(1.dp, Color.Gray)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(drawMode, state.failureType, canvasSize) {
                        if (drawMode) {
                            detectDragGestures(
                                onDragStart = { offset -> dragStart = offset; dragCurrent = offset },
                                onDrag = { _, drag -> dragCurrent = dragCurrent?.plus(drag) },
                                onDragEnd = {
                                    val s = dragStart; val e = dragCurrent
                                    if (s != null && e != null && scaleX > 0f && scaleY > 0f) {
                                        val ix1 = (s.x / scaleX).toInt().coerceIn(0, mask.width - 1)
                                        val iy1 = (s.y / scaleY).toInt().coerceIn(0, mask.height - 1)
                                        val ix2 = (e.x / scaleX).toInt().coerceIn(0, mask.width - 1)
                                        val iy2 = (e.y / scaleY).toInt().coerceIn(0, mask.height - 1)
                                        if (state.failureType == PanelDetectionFailureType.MissedPanel) {
                                            viewModel.addDrawnPanel(ix1, iy1, ix2, iy2)
                                        } else {
                                            viewModel.addDrawnBoundary(ix1, iy1, ix2, iy2)
                                        }
                                    }
                                    dragStart = null; dragCurrent = null
                                },
                                onDragCancel = { dragStart = null; dragCurrent = null },
                            )
                        } else {
                            detectTapGestures { offset ->
                                if (scaleX > 0f && scaleY > 0f) {
                                    viewModel.onTap(
                                        tappedImageX = (offset.x / scaleX).toInt().coerceIn(0, mask.width - 1),
                                        tappedImageY = (offset.y / scaleY).toInt().coerceIn(0, mask.height - 1),
                                    )
                                }
                            }
                        }
                    },
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawImage(
                        image = maskBitmap,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    )

                    // Detected panels
                    viewModel.detectedPanels.forEachIndexed { i, p ->
                        val selected = state.tappedPanelIndex == i
                        drawRect(
                            color = if (selected) Color.Red else Color.Blue,
                            topLeft = Offset(p.x * scaleX, p.y * scaleY),
                            size = Size(p.width * scaleX, p.height * scaleY),
                            style = Stroke(width = if (selected) 3f else 1.5f),
                        )
                    }

                    // Tap marker (non-draw mode)
                    val tx = state.tappedX; val ty = state.tappedY
                    if (tx != null && ty != null && state.tappedPanelIndex == null) {
                        drawCircle(color = Color.Magenta, radius = 10f, center = Offset(tx * scaleX, ty * scaleY))
                    }

                    // Committed drawn panels (green)
                    state.drawnPanels.forEach { p ->
                        drawRect(
                            color = Color(0xFF4CAF50),
                            topLeft = Offset(p.x * scaleX, p.y * scaleY),
                            size = Size(p.width * scaleX, p.height * scaleY),
                            style = Stroke(width = 2f),
                        )
                    }

                    // Committed boundary lines (green)
                    state.drawnBoundaries.forEach { b ->
                        drawLine(
                            color = Color(0xFF4CAF50),
                            start = Offset(b.x1 * scaleX, b.y1 * scaleY),
                            end = Offset(b.x2 * scaleX, b.y2 * scaleY),
                            strokeWidth = 2f,
                        )
                    }

                    // In-progress draw preview (yellow)
                    val s = dragStart; val e = dragCurrent
                    if (s != null && e != null) {
                        if (state.failureType == PanelDetectionFailureType.MissedPanel) {
                            drawRect(
                                color = Color.Yellow,
                                topLeft = Offset(minOf(s.x, e.x), minOf(s.y, e.y)),
                                size = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y)),
                                style = Stroke(width = 2f),
                            )
                        } else {
                            drawLine(color = Color.Yellow, start = s, end = e, strokeWidth = 2f)
                        }
                    }
                }
            }

            // Clear last button in draw mode
            if (drawMode) {
                val hasDrawn = state.drawnPanels.isNotEmpty() || state.drawnBoundaries.isNotEmpty()
                if (hasDrawn) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = {
                            if (state.failureType == PanelDetectionFailureType.MissedPanel)
                                viewModel.clearLastDrawnPanel()
                            else
                                viewModel.clearLastDrawnBoundary()
                        }) {
                            Text("Clear last")
                        }
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
                Text("Created: ${state.submittedIssueUrl}", color = Color(0xFF388E3C))
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
