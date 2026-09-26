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

/**
 * Капля полива — Ionicons `water` (слайд 2 welcome). Контур капли (скруглённый
 * клин от острия внизу к широкой дуге наверху) тем же простым стилем.
 */
@Composable
fun GtWaterDropMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.2f, size.height * 0.2f),
            size = Size(size.width * 0.6f, size.height * 0.5f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.2f, size.height * 0.45f),
            end = Offset(size.width * 0.5f, size.height * 0.88f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.8f, size.height * 0.45f),
            end = Offset(size.width * 0.5f, size.height * 0.88f),
            strokeWidth = stroke.width,
        )
    }
}

/** Колокольчик напоминаний — Ionicons `notifications` (слайд 3 welcome). */
@Composable
fun GtBellMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        // Купол колокола: верхняя половина окружности + расширяющиеся стенки.
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.24f, size.height * 0.2f),
            size = Size(size.width * 0.52f, size.height * 0.5f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.24f, size.height * 0.45f),
            end = Offset(size.width * 0.16f, size.height * 0.68f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.76f, size.height * 0.45f),
            end = Offset(size.width * 0.84f, size.height * 0.68f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.16f, size.height * 0.68f),
            end = Offset(size.width * 0.84f, size.height * 0.68f),
            strokeWidth = stroke.width,
        )
        // Язычок.
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.06f,
            center = Offset(size.width * 0.5f, size.height * 0.82f),
            style = stroke,
        )
    }
}

// ---------------------------------------------------------------------------
// Метки login (screen-login M7): person-add / key / lock-closed / warning /
// checkmark-circle / copy — Ionicons тех же имён тем же простым стилем.
// ---------------------------------------------------------------------------

/** «Создать аккаунт» — Ionicons `person-add`: голова + плечи + плюс. */
@Composable
fun GtPersonAddMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = (Spacing.xxs / 4).toPx()
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.14f,
            center = Offset(size.width * 0.38f, size.height * 0.3f),
            style = Stroke(width = stroke),
        )
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.12f, size.height * 0.48f),
            size = Size(size.width * 0.52f, size.height * 0.44f),
            style = Stroke(width = stroke),
        )
        // Плюс.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.74f, size.height * 0.34f),
            end = Offset(size.width * 0.74f, size.height * 0.66f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.58f, size.height * 0.5f),
            end = Offset(size.width * 0.9f, size.height * 0.5f),
            strokeWidth = stroke,
        )
    }
}

/** Ключ — Ionicons `key`: головка-круг + стержень с бородкой (login/login-key). */
@Composable
fun GtKeyMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        // Головка.
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.18f,
            center = Offset(size.width * 0.3f, size.height * 0.34f),
            style = stroke,
        )
        // Стержень по диагонали.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.42f, size.height * 0.46f),
            end = Offset(size.width * 0.82f, size.height * 0.8f),
            strokeWidth = stroke.width,
        )
        // Бородка (два зубца вниз от стержня).
        drawLine(
            color = tint,
            start = Offset(size.width * 0.68f, size.height * 0.66f),
            end = Offset(size.width * 0.76f, size.height * 0.74f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.8f, size.height * 0.7f),
            end = Offset(size.width * 0.88f, size.height * 0.78f),
            strokeWidth = stroke.width,
        )
    }
}

/** Закрытый замок — Ionicons `lock-closed` (приватная заметка choose). */
@Composable
fun GtLockMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        // Дужка.
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.28f, size.height * 0.14f),
            size = Size(size.width * 0.44f, size.height * 0.42f),
            style = stroke,
        )
        // Корпус.
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.18f, size.height * 0.42f),
            size = Size(size.width * 0.64f, size.height * 0.46f),
            cornerRadius = CornerRadius(stroke.width * 2),
            style = stroke,
        )
    }
}

/** Предупреждение — Ionicons `warning` (треугольник с восклицательным знаком). */
@Composable
fun GtWarningMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        val top = Offset(size.width * 0.5f, size.height * 0.12f)
        val bottomLeft = Offset(size.width * 0.1f, size.height * 0.88f)
        val bottomRight = Offset(size.width * 0.9f, size.height * 0.88f)
        drawLine(tint, top, bottomLeft, stroke.width)
        drawLine(tint, top, bottomRight, stroke.width)
        drawLine(tint, bottomLeft, bottomRight, stroke.width)
        // Восклицательный знак.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.5f, size.height * 0.42f),
            end = Offset(size.width * 0.5f, size.height * 0.62f),
            strokeWidth = stroke.width,
        )
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.035f,
            center = Offset(size.width * 0.5f, size.height * 0.74f),
            style = stroke,
        )
    }
}

/** Успех — Ionicons `checkmark-circle` (#22c55e show-key). */
@Composable
fun GtCheckMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.44f,
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.3f, size.height * 0.52f),
            end = Offset(size.width * 0.45f, size.height * 0.66f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.45f, size.height * 0.66f),
            end = Offset(size.width * 0.72f, size.height * 0.36f),
            strokeWidth = stroke.width,
        )
    }
}

/** Копирование — Ionicons `copy-outline` (два скруглённых прямоугольника). */
@Composable
fun GtCopyMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        // Задний лист (полный).
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.3f, size.height * 0.14f),
            size = Size(size.width * 0.52f, size.height * 0.6f),
            cornerRadius = CornerRadius(stroke.width * 2),
            style = stroke,
        )
        // Передний лист (перекрывает низ заднего).
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.16f, size.height * 0.34f),
            size = Size(size.width * 0.52f, size.height * 0.54f),
            cornerRadius = CornerRadius(stroke.width * 2),
            style = stroke,
        )
    }
}
