package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Простые метки вместо Ionicons: material-icons в пинах миссии нет.
 * Толщина линии — четверть [Spacing.xxs] (тот же шаг, что рамка контрола).
 * [tint] приходит из темы, здесь цвет не собирается.
 */
@Composable
fun GtCalendarMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        val left = size.width * 0.12f
        val top = size.height * 0.22f
        val body = Size(size.width - left * 2, size.height * 0.66f)
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = body,
            cornerRadius = CornerRadius(stroke.width * 2),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(left, size.height * 0.42f),
            end = Offset(size.width - left, size.height * 0.42f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.32f, size.height * 0.12f),
            end = Offset(size.width * 0.32f, size.height * 0.32f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.68f, size.height * 0.12f),
            end = Offset(size.width * 0.68f, size.height * 0.32f),
            strokeWidth = stroke.width,
        )
    }
}

@Composable
fun GtChevronMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = (Spacing.xxs / 4).toPx()
        val mid = Offset(size.width * 0.5f, size.height * 0.62f)
        drawLine(tint, Offset(size.width * 0.22f, size.height * 0.38f), mid, stroke)
        drawLine(tint, mid, Offset(size.width * 0.78f, size.height * 0.38f), stroke)
    }
}

@Composable
fun GtCameraMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        val left = size.width * 0.1f
        val top = size.height * 0.28f
        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(size.width - left * 2, size.height * 0.56f),
            cornerRadius = CornerRadius(stroke.width * 2),
            style = stroke,
        )
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.16f,
            center = Offset(size.width * 0.5f, size.height * 0.56f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.32f, top),
            end = Offset(size.width * 0.42f, size.height * 0.16f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.42f, size.height * 0.16f),
            end = Offset(size.width * 0.62f, size.height * 0.16f),
            strokeWidth = stroke.width,
        )
    }
}

@Composable
fun GtLeafMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        drawOval(
            color = tint,
            topLeft = Offset(size.width * 0.28f, size.height * 0.12f),
            size = Size(size.width * 0.48f, size.height * 0.62f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.38f, size.height * 0.86f),
            end = Offset(size.width * 0.62f, size.height * 0.22f),
            strokeWidth = stroke.width,
        )
    }
}

/** Метка вкладки «Профиль» — Ionicons `person` тем же простым стилем (голова + плечи). */
@Composable
fun GtPersonMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.18f,
            center = Offset(size.width * 0.5f, size.height * 0.3f),
            style = stroke,
        )
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.2f, size.height * 0.48f),
            size = Size(size.width * 0.6f, size.height * 0.56f),
            style = stroke,
        )
    }
}
