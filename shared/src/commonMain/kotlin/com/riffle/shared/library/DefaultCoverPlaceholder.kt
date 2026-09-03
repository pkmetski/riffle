package com.riffle.shared.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform

@Composable
fun DefaultCoverPlaceholder(isAudiobook: Boolean, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val gradientStart: Color
    val gradientEnd: Color
    val glyphColor: Color
    if (isAudiobook) {
        gradientStart = if (dark) Color(0xFF1C3040) else Color(0xFFD9EFF8)
        gradientEnd   = if (dark) Color(0xFF0F1E2A) else Color(0xFF9ECDE6)
        glyphColor    = if (dark) Color(0xFF7DCAEC) else Color(0xFF0E5F8A)
    } else {
        gradientStart = if (dark) Color(0xFF352B4A) else Color(0xFFEAE0F8)
        gradientEnd   = if (dark) Color(0xFF1F1830) else Color(0xFFC9B5E6)
        glyphColor    = if (dark) Color(0xFFCDB8FF) else Color(0xFF5B3FA0)
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(gradientStart, gradientEnd),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            )
        )
        if (isAudiobook) drawWaveformGlyph(glyphColor) else drawShelfGlyph(glyphColor)
    }
}

private fun DrawScope.drawShelfGlyph(color: Color) {
    val glyphW = size.width * 0.58f
    val glyphH = size.height * 0.58f
    val ox = (size.width - glyphW) / 2f
    val oy = (size.height - glyphH) / 2f
    val sx = glyphW / 42f
    val sy = glyphH / 38f

    fun x(v: Float) = ox + v * sx
    fun y(v: Float) = oy + v * sy
    fun w(v: Float) = v * sx
    fun h(v: Float) = v * sy
    fun cr(v: Float) = CornerRadius(v * sx, v * sy)

    drawLine(
        color = color.copy(alpha = 0.5f),
        start = Offset(x(0f), y(37f)),
        end = Offset(x(42f), y(37f)),
        strokeWidth = h(2.5f),
        cap = StrokeCap.Round,
    )
    drawRoundRect(color = color.copy(alpha = 0.85f), topLeft = Offset(x(1f), y(6f)), size = Size(w(9f), h(29.5f)), cornerRadius = cr(2f))
    withTransform({ rotate(-4f, Offset(x(17f), y(18.75f))) }) {
        drawRoundRect(color = color.copy(alpha = 0.6f), topLeft = Offset(x(11.5f), y(2f)), size = Size(w(11f), h(33.5f)), cornerRadius = cr(2f))
    }
    drawRoundRect(color = color.copy(alpha = 0.4f), topLeft = Offset(x(28f), y(4f)), size = Size(w(10f), h(31.5f)), cornerRadius = cr(2f))
}

private fun DrawScope.drawWaveformGlyph(color: Color) {
    val glyphW = size.width * 0.52f
    val glyphH = size.height * 0.44f
    val ox = (size.width - glyphW) / 2f
    val oy = (size.height - glyphH) / 2f
    val sx = glyphW / 48f
    val sy = glyphH / 32f

    data class Bar(val x: Float, val y: Float, val h: Float, val alpha: Float)
    listOf(
        Bar(0f, 14f, 4f, 0.35f), Bar(6f, 10f, 12f, 0.55f), Bar(12f, 4f, 24f, 0.80f),
        Bar(18f, 0f, 32f, 1.00f), Bar(24f, 7f, 18f, 0.70f), Bar(30f, 2f, 28f, 0.90f),
        Bar(36f, 9f, 14f, 0.60f), Bar(42f, 13f, 6f, 0.38f),
    ).forEach { bar ->
        drawRoundRect(
            color = color.copy(alpha = bar.alpha),
            topLeft = Offset(ox + bar.x * sx, oy + bar.y * sy),
            size = Size(4f * sx, bar.h * sy),
            cornerRadius = CornerRadius(2f * sx, 2f * sy),
        )
    }
}
