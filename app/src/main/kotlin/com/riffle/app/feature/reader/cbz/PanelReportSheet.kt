package com.riffle.app.feature.reader.cbz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
    maskBitmap: ImageBitmap,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val cutOffMode = state.failureType == PanelDetectionFailureType.CutPanelCutOff
    val drawMode = state.failureType == PanelDetectionFailureType.MissedPanel ||
        state.failureType == PanelDetectionFailureType.MergedPanels ||
        state.failureType == PanelDetectionFailureType.SplitPanel ||
        cutOffMode
    val drawsRectangle = state.failureType == PanelDetectionFailureType.MissedPanel ||
        state.failureType == PanelDetectionFailureType.SplitPanel
    val orderMode = state.failureType == PanelDetectionFailureType.WrongPanelOrder

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_report_panel_detection_issue),
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
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
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

            val labelPaint = remember {
                android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 36f
                    isFakeBoldText = true
                    textAlign = android.graphics.Paint.Align.CENTER
                    setShadowLayer(3f, 1f, 1f, android.graphics.Color.BLACK)
                }
            }
            val orderedIndexMap = remember(state.orderedPanelIndices) {
                state.orderedPanelIndices.withIndex().associate { (pos, idx) -> idx to pos }
            }

            var canvasSize by remember { mutableStateOf(IntSize.Zero) }
            // Panel coordinates are in the original image space (pagePanels.imageWidth × imageHeight).
            // Use imageWidth/imageHeight (not mask.width/height) as the reference so rectangles
            // stay correctly positioned even when the mask bitmap was decoded at a different
            // resolution (e.g. DPI-scaled by BitmapFactory inScaled=true).
            val imageW = viewModel.imageWidth.takeIf { it > 0 } ?: mask.width
            val imageH = viewModel.imageHeight.takeIf { it > 0 } ?: mask.height
            val scaleX = if (canvasSize.width > 0 && imageW > 0) canvasSize.width.toFloat() / imageW else 1f
            val scaleY = if (canvasSize.height > 0 && imageH > 0) canvasSize.height.toFloat() / imageH else 1f

            // In-progress draw state (canvas coords, local only)
            var dragStart by remember { mutableStateOf<Offset?>(null) }
            var dragCurrent by remember { mutableStateOf<Offset?>(null) }

            if (drawMode) {
                val hint = when (state.failureType) {
                    PanelDetectionFailureType.MissedPanel,
                    PanelDetectionFailureType.SplitPanel -> "Draw the correct panel boundary"
                    PanelDetectionFailureType.CutPanelCutOff ->
                        "Tap the cut-off panel to identify it; drag to draw its correct boundary"
                    else -> "Draw panel boundary lines"
                }
                Text(hint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
            if (orderMode) {
                Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_tap_panels_in_the_correct_reading_order_tap_a_numbered_panel_to_reset_from_that),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(mask.width.toFloat() / mask.height.toFloat())
                    .border(1.dp, Color.Gray)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(cutOffMode, drawMode, orderMode, state.failureType, canvasSize) {
                        when {
                            cutOffMode -> awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val startPos = down.position
                                var currentPos = startPos
                                var crossedSlop = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    if (!change.pressed) break
                                    currentPos = change.position
                                    if (!crossedSlop) {
                                        val dist = kotlin.math.hypot(
                                            (currentPos.x - startPos.x).toDouble(),
                                            (currentPos.y - startPos.y).toDouble(),
                                        )
                                        if (dist > viewConfiguration.touchSlop) {
                                            crossedSlop = true
                                            dragStart = startPos
                                        }
                                    }
                                    if (crossedSlop) dragCurrent = currentPos
                                }
                                if (scaleX > 0f && scaleY > 0f) {
                                    if (!crossedSlop) {
                                        val ix = (startPos.x / scaleX).toInt().coerceIn(0, mask.width - 1)
                                        val iy = (startPos.y / scaleY).toInt().coerceIn(0, mask.height - 1)
                                        viewModel.onTap(tappedImageX = ix, tappedImageY = iy)
                                    } else {
                                        val ix1 = (startPos.x / scaleX).toInt().coerceIn(0, mask.width - 1)
                                        val iy1 = (startPos.y / scaleY).toInt().coerceIn(0, mask.height - 1)
                                        val ix2 = (currentPos.x / scaleX).toInt().coerceIn(0, mask.width - 1)
                                        val iy2 = (currentPos.y / scaleY).toInt().coerceIn(0, mask.height - 1)
                                        viewModel.addDrawnPanel(ix1, iy1, ix2, iy2)
                                    }
                                }
                                dragStart = null; dragCurrent = null
                            }
                            drawMode -> detectDragGestures(
                                onDragStart = { offset -> dragStart = offset; dragCurrent = offset },
                                onDrag = { _, drag -> dragCurrent = dragCurrent?.plus(drag) },
                                onDragEnd = {
                                    val s = dragStart; val e = dragCurrent
                                    if (s != null && e != null && scaleX > 0f && scaleY > 0f) {
                                        val ix1 = (s.x / scaleX).toInt().coerceIn(0, mask.width - 1)
                                        val iy1 = (s.y / scaleY).toInt().coerceIn(0, mask.height - 1)
                                        val ix2 = (e.x / scaleX).toInt().coerceIn(0, mask.width - 1)
                                        val iy2 = (e.y / scaleY).toInt().coerceIn(0, mask.height - 1)
                                        if (drawsRectangle) {
                                            viewModel.addDrawnPanel(ix1, iy1, ix2, iy2)
                                        } else {
                                            viewModel.addDrawnBoundary(ix1, iy1, ix2, iy2)
                                        }
                                    }
                                    dragStart = null; dragCurrent = null
                                },
                                onDragCancel = { dragStart = null; dragCurrent = null },
                            )
                            else -> detectTapGestures { offset ->
                                if (scaleX > 0f && scaleY > 0f) {
                                    val ix = (offset.x / scaleX).toInt().coerceIn(0, mask.width - 1)
                                    val iy = (offset.y / scaleY).toInt().coerceIn(0, mask.height - 1)
                                    if (orderMode) {
                                        viewModel.tapForOrder(ix, iy)
                                    } else {
                                        viewModel.onTap(tappedImageX = ix, tappedImageY = iy)
                                    }
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
                        val orderPos = orderedIndexMap[i]
                        val isOrdered = orderPos != null
                        val selected = state.tappedPanelIndex == i
                        drawPanelReportOutline(
                            topLeft = Offset(p.x * scaleX, p.y * scaleY),
                            size = Size(p.width * scaleX, p.height * scaleY),
                            style = panelReportOutlineStyle(selected = selected, ordered = isOrdered),
                        )
                        if (orderPos != null) {
                            drawIntoCanvas { canvas ->
                                canvas.nativeCanvas.drawText(
                                    "${orderPos + 1}",
                                    (p.x + p.width / 2f) * scaleX,
                                    (p.y + p.height / 2f) * scaleY + labelPaint.textSize / 2f - labelPaint.descent(),
                                    labelPaint,
                                )
                            }
                        }
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
                        if (drawsRectangle || cutOffMode) {
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
                            if (drawsRectangle || cutOffMode)
                                viewModel.clearLastDrawnPanel()
                            else
                                viewModel.clearLastDrawnBoundary()
                        }) {
                            Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_clear_last))
                        }
                    }
                }
            }

            OutlinedTextField(
                value = state.notes,
                onValueChange = viewModel::setNotes,
                label = { Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_notes_optional)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            if (state.error != null) {
                Text(state.error!!, color = Color.Red)
            }

            val submittedIssueUrl = state.submittedIssueUrl
            if (submittedIssueUrl != null) {
                Text(
                    androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_panel_report_created, submittedIssueUrl),
                    color = Color(0xFF388E3C),
                )
            }

            Button(
                onClick = onSubmit,
                enabled = !state.submitting && state.submittedIssueUrl == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.submitting) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else Text(androidx.compose.ui.res.stringResource(com.riffle.app.R.string.ui_submit_to_github))
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

internal data class PanelReportOutlineStyle(
    val haloColor: Color,
    val haloWidth: Float,
    val contrastColor: Color,
    val contrastWidth: Float,
    val foregroundColor: Color,
    val foregroundWidth: Float,
)

internal fun panelReportOutlineStyle(
    selected: Boolean,
    ordered: Boolean,
): PanelReportOutlineStyle {
    val foreground = when {
        ordered -> Color(0xFFFF9800)
        selected -> Color.Red
        else -> Color(0xFF00B8FF)
    }
    val foregroundWidth = if (selected || ordered) 3.5f else 2.75f
    return PanelReportOutlineStyle(
        haloColor = Color(0xE6000000),
        haloWidth = foregroundWidth + 4f,
        contrastColor = Color.White,
        contrastWidth = foregroundWidth + 2f,
        foregroundColor = foreground,
        foregroundWidth = foregroundWidth,
    )
}

private fun DrawScope.drawPanelReportOutline(
    topLeft: Offset,
    size: Size,
    style: PanelReportOutlineStyle,
) {
    drawRect(
        color = style.haloColor,
        topLeft = topLeft,
        size = size,
        style = Stroke(width = style.haloWidth),
    )
    drawRect(
        color = style.contrastColor,
        topLeft = topLeft,
        size = size,
        style = Stroke(width = style.contrastWidth),
    )
    drawRect(
        color = style.foregroundColor,
        topLeft = topLeft,
        size = size,
        style = Stroke(width = style.foregroundWidth),
    )
}
