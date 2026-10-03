package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
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

/** Куда смотрит [GtChevronMark]; исходный рисунок — [Down]. */
enum class GtChevronDirection(internal val degrees: Float) {
    Down(0f),
    Left(90f),
    Up(180f),
    Right(270f),
}

@Composable
fun GtChevronMark(
    tint: Color,
    modifier: Modifier = Modifier,
    direction: GtChevronDirection = GtChevronDirection.Down,
) {
    Canvas(modifier.graphicsLayer { rotationZ = direction.degrees }) {
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
 * Капля полива — Ionicons `water` / `water-outline` (слайд 2 welcome, пилюли
 * статуса, кнопка полива). Острие вверху, круглое дно: касательные из острия
 * к окружности (угол между ними 60°) и дуга 240° снизу.
 * [filled] — сплошная капля, как `water` на кнопке; по умолчанию контур.
 */
@Composable
fun GtWaterDropMark(tint: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Canvas(modifier) {
        val radius = size.minDimension * 0.29f
        val centerX = size.width * 0.5f
        val centerY = size.height * 0.65f
        val tangentRad = Math.toRadians(30.0)
        val tangentX = radius * kotlin.math.cos(tangentRad).toFloat()
        val tangentY = radius * kotlin.math.sin(tangentRad).toFloat()
        val drop = Path().apply {
            moveTo(centerX, centerY - radius * 2)
            lineTo(centerX + tangentX, centerY - tangentY)
            arcTo(
                rect = Rect(Offset(centerX, centerY), radius),
                startAngleDegrees = -30f,
                sweepAngleDegrees = 240f,
                forceMoveTo = false,
            )
            close()
        }
        if (filled) {
            drawPath(drop, tint)
        } else {
            drawPath(
                drop,
                tint,
                style = Stroke(
                    width = (Spacing.xxs / 4).toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
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

/** Галочка без круга: состояние «выполнено» внутри кнопки, где кольцо дало бы двойную рамку. */
@Composable
fun GtTickMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = (Spacing.xxs / 2).toPx()
        val elbow = Offset(size.width * 0.4f, size.height * 0.72f)
        drawLine(tint, Offset(size.width * 0.14f, size.height * 0.5f), elbow, stroke, StrokeCap.Round)
        drawLine(tint, elbow, Offset(size.width * 0.86f, size.height * 0.24f), stroke, StrokeCap.Round)
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

// ---------------------------------------------------------------------------
// Метки profile (screen-profile M7): notifications / time / paper-plane /
// refresh / log-out / person / language / palette — Ionicons тех же имён тем
// же простым стилем.
// ---------------------------------------------------------------------------

/** Колокольчик контурный — Ionicons `notifications-outline` (строка пушей). */
@Composable
fun GtBellOutlineMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.24f, size.height * 0.18f),
            size = Size(size.width * 0.52f, size.height * 0.5f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.24f, size.height * 0.43f),
            end = Offset(size.width * 0.14f, size.height * 0.68f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.76f, size.height * 0.43f),
            end = Offset(size.width * 0.86f, size.height * 0.68f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.14f, size.height * 0.68f),
            end = Offset(size.width * 0.86f, size.height * 0.68f),
            strokeWidth = stroke.width,
        )
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.06f,
            center = Offset(size.width * 0.5f, size.height * 0.82f),
            style = stroke,
        )
    }
}

/** Циферблат — Ionicons `time-outline` (время уведомления). */
@Composable
fun GtClockMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.42f,
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.5f, size.height * 0.5f),
            end = Offset(size.width * 0.5f, size.height * 0.24f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.5f, size.height * 0.5f),
            end = Offset(size.width * 0.72f, size.height * 0.62f),
            strokeWidth = stroke.width,
        )
    }
}

/** Бумажный самолётик — Ionicons `paper-plane-outline` (тестовое уведомление). */
@Composable
fun GtSendMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        val tip = Offset(size.width * 0.88f, size.height * 0.12f)
        val bottom = Offset(size.width * 0.12f, size.height * 0.56f)
        val mid = Offset(size.width * 0.6f, size.height * 0.62f)
        val low = Offset(size.width * 0.5f, size.height * 0.9f)
        drawLine(tint, tip, bottom, stroke.width)
        drawLine(tint, tip, mid, stroke.width)
        drawLine(tint, bottom, mid, stroke.width)
        drawLine(tint, mid, low, stroke.width)
        drawLine(tint, low, tip, stroke.width)
    }
}

/** Стрелка обновления — Ionicons `refresh` (регенерация ключа). */
@Composable
fun GtRefreshMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        drawArc(
            color = tint,
            startAngle = -40f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(size.width * 0.15f, size.height * 0.15f),
            size = Size(size.width * 0.7f, size.height * 0.7f),
            style = stroke,
        )
        // Наконечник стрелки.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.86f, size.height * 0.1f),
            end = Offset(size.width * 0.86f, size.height * 0.36f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.86f, size.height * 0.1f),
            end = Offset(size.width * 0.62f, size.height * 0.14f),
            strokeWidth = stroke.width,
        )
    }
}

/** Глобус — Ionicons `language-outline` (язык). */
@Composable
fun GtGlobeMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.42f,
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            style = stroke,
        )
        drawOval(
            color = tint,
            topLeft = Offset(size.width * 0.3f, size.height * 0.08f),
            size = Size(size.width * 0.4f, size.height * 0.84f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.08f, size.height * 0.5f),
            end = Offset(size.width * 0.9f, size.height * 0.5f),
            strokeWidth = stroke.width,
        )
    }
}

/** Палитра — Ionicons `color-palette-outline` (тема). */
@Composable
fun GtPaletteMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        drawArc(
            color = tint,
            startAngle = 150f,
            sweepAngle = 240f,
            useCenter = false,
            topLeft = Offset(size.width * 0.12f, size.height * 0.12f),
            size = Size(size.width * 0.76f, size.height * 0.76f),
            style = stroke,
        )
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.07f,
            center = Offset(size.width * 0.5f, size.height * 0.28f),
            style = stroke,
        )
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.07f,
            center = Offset(size.width * 0.32f, size.height * 0.5f),
            style = stroke,
        )
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.07f,
            center = Offset(size.width * 0.62f, size.height * 0.5f),
            style = stroke,
        )
    }
}

/** Выход — Ionicons `log-out-outline` (выход из аккаунта). */
@Composable
fun GtSignOutMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        // Дверь (скобка).
        drawLine(tint, Offset(size.width * 0.3f, size.height * 0.12f), Offset(size.width * 0.12f, size.height * 0.12f), stroke.width)
        drawLine(tint, Offset(size.width * 0.12f, size.height * 0.12f), Offset(size.width * 0.12f, size.height * 0.88f), stroke.width)
        drawLine(tint, Offset(size.width * 0.12f, size.height * 0.88f), Offset(size.width * 0.3f, size.height * 0.88f), stroke.width)
        // Стрелка наружу.
        drawLine(tint, Offset(size.width * 0.38f, size.height * 0.5f), Offset(size.width * 0.9f, size.height * 0.5f), stroke.width)
        drawLine(tint, Offset(size.width * 0.68f, size.height * 0.28f), Offset(size.width * 0.9f, size.height * 0.5f), stroke.width)
        drawLine(tint, Offset(size.width * 0.68f, size.height * 0.72f), Offset(size.width * 0.9f, size.height * 0.5f), stroke.width)
    }
}

/** Контраст авто-темы — Ionicons `contrast-outline` (пикер темы: auto). */
@Composable
fun GtContrastMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.42f,
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            style = stroke,
        )
        drawArc(
            color = tint,
            startAngle = 90f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset(size.width * 0.08f, size.height * 0.08f),
            size = Size(size.width * 0.84f, size.height * 0.84f),
        )
    }
}

/** Солнце — Ionicons `sunny-outline` (пикер темы: light). */
@Composable
fun GtSunMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.26f,
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            style = stroke,
        )
        // 8 лучей.
        for (index in 0 until 8) {
            val angle = Math.PI * index / 4.0
            val innerR = size.minDimension * 0.36f
            val outerR = size.minDimension * 0.46f
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            drawLine(
                color = tint,
                start = Offset(cx + innerR * kotlin.math.cos(angle).toFloat(), cy + innerR * kotlin.math.sin(angle).toFloat()),
                end = Offset(cx + outerR * kotlin.math.cos(angle).toFloat(), cy + outerR * kotlin.math.sin(angle).toFloat()),
                strokeWidth = stroke.width,
            )
        }
    }
}

/** Луна — Ionicons `moon-outline` (пикер темы: dark). */
@Composable
fun GtMoonMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        // Серп: внешняя дуга + внутренняя вырезка (двумя дугами).
        drawArc(
            color = tint,
            startAngle = 160f,
            sweepAngle = 220f,
            useCenter = false,
            topLeft = Offset(size.width * 0.14f, size.height * 0.1f),
            size = Size(size.width * 0.72f, size.height * 0.8f),
            style = stroke,
        )
        drawArc(
            color = tint,
            startAngle = 300f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(size.width * 0.3f, size.height * 0.26f),
            size = Size(size.width * 0.44f, size.height * 0.5f),
            style = stroke,
        )
    }
}

// ---------------------------------------------------------------------------
// Метки plant detail (screen-plant-detail M7): create-outline / location /
// leaf(есть) / pot / scissors / settings / trash / images — Ionicons тех же
// имён тем же простым стилем.
// ---------------------------------------------------------------------------

/** Карандаш — Ionicons `create-outline` (инлайн-редактирование имени). */
@Composable
fun GtPencilMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = (Spacing.xxs / 4).toPx()
        // Корпус по диагонали.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.25f, size.height * 0.75f),
            end = Offset(size.width * 0.72f, size.height * 0.28f),
            strokeWidth = stroke,
        )
        // Грань (вторая линия корпуса).
        drawLine(
            color = tint,
            start = Offset(size.width * 0.33f, size.height * 0.83f),
            end = Offset(size.width * 0.8f, size.height * 0.36f),
            strokeWidth = stroke,
        )
        // Остриё.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.25f, size.height * 0.75f),
            end = Offset(size.width * 0.16f, size.height * 0.9f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.16f, size.height * 0.9f),
            end = Offset(size.width * 0.33f, size.height * 0.83f),
            strokeWidth = stroke,
        )
    }
}

/** Булавка локации — Ionicons `location-outline` (строка локации в шапке). */
@Composable
fun GtLocationMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        // Купол булавки.
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.28f, size.height * 0.1f),
            size = Size(size.width * 0.44f, size.height * 0.44f),
            style = stroke,
        )
        // Стенки к острию.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.28f, size.height * 0.32f),
            end = Offset(size.width * 0.5f, size.height * 0.9f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.72f, size.height * 0.32f),
            end = Offset(size.width * 0.5f, size.height * 0.9f),
            strokeWidth = stroke.width,
        )
    }
}

/** Горшок с растением — Ionicons `bulb`-стиль для пересадки (RN 🪴). */
@Composable
fun GtPotMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        // Листья: два овала-лепестка над горшком.
        drawOval(
            color = tint,
            topLeft = Offset(size.width * 0.12f, size.height * 0.08f),
            size = Size(size.width * 0.34f, size.height * 0.34f),
            style = stroke,
        )
        drawOval(
            color = tint,
            topLeft = Offset(size.width * 0.54f, size.height * 0.08f),
            size = Size(size.width * 0.34f, size.height * 0.34f),
            style = stroke,
        )
        // Стебли в горшок.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.29f, size.height * 0.4f),
            end = Offset(size.width * 0.42f, size.height * 0.56f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.71f, size.height * 0.4f),
            end = Offset(size.width * 0.58f, size.height * 0.56f),
            strokeWidth = stroke.width,
        )
        // Корпус горшка (трапеция тремя линиями).
        drawLine(
            color = tint,
            start = Offset(size.width * 0.24f, size.height * 0.56f),
            end = Offset(size.width * 0.76f, size.height * 0.56f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.32f, size.height * 0.56f),
            end = Offset(size.width * 0.38f, size.height * 0.9f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.68f, size.height * 0.56f),
            end = Offset(size.width * 0.62f, size.height * 0.9f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.38f, size.height * 0.9f),
            end = Offset(size.width * 0.62f, size.height * 0.9f),
            strokeWidth = stroke.width,
        )
    }
}

/** Ножницы — Ionicons `scissors` (RN ✂️, обрезка). */
@Composable
fun GtScissorsMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        // Лезвия крест-накрест.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.28f, size.height * 0.14f),
            end = Offset(size.width * 0.72f, size.height * 0.62f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.72f, size.height * 0.14f),
            end = Offset(size.width * 0.28f, size.height * 0.62f),
            strokeWidth = stroke.width,
        )
        // Кольца-ручки.
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.12f,
            center = Offset(size.width * 0.3f, size.height * 0.78f),
            style = stroke,
        )
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.12f,
            center = Offset(size.width * 0.7f, size.height * 0.78f),
            style = stroke,
        )
    }
}

/** Шестерёнка — Ionicons `settings-outline` (кнопка настроек ухода). */
@Composable
fun GtGearMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.3f,
            center = Offset(size.width * 0.5f, size.height * 0.5f),
            style = stroke,
        )
        // 8 зубцов-спиц наружу.
        for (index in 0 until 8) {
            val angle = Math.PI * index / 4.0
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val innerR = size.minDimension * 0.3f
            val outerR = size.minDimension * 0.42f
            drawLine(
                color = tint,
                start = Offset(cx + innerR * kotlin.math.cos(angle).toFloat(), cy + innerR * kotlin.math.sin(angle).toFloat()),
                end = Offset(cx + outerR * kotlin.math.cos(angle).toFloat(), cy + outerR * kotlin.math.sin(angle).toFloat()),
                strokeWidth = stroke.width,
            )
        }
    }
}

/** Корзина — Ionicons `trash-outline` (кнопка удаления). */
@Composable
fun GtTrashMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        // Корпус.
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.24f, size.height * 0.3f),
            size = Size(size.width * 0.52f, size.height * 0.6f),
            cornerRadius = CornerRadius(stroke.width * 2),
            style = stroke,
        )
        // Крышка.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.16f, size.height * 0.28f),
            end = Offset(size.width * 0.84f, size.height * 0.28f),
            strokeWidth = stroke.width,
        )
        // Ручка крышки.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.4f, size.height * 0.28f),
            end = Offset(size.width * 0.4f, size.height * 0.16f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.4f, size.height * 0.16f),
            end = Offset(size.width * 0.6f, size.height * 0.16f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.6f, size.height * 0.16f),
            end = Offset(size.width * 0.6f, size.height * 0.28f),
            strokeWidth = stroke.width,
        )
        // Две штриха на корпусе.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.42f, size.height * 0.42f),
            end = Offset(size.width * 0.42f, size.height * 0.74f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.58f, size.height * 0.42f),
            end = Offset(size.width * 0.58f, size.height * 0.74f),
            strokeWidth = stroke.width,
        )
    }
}

/** Галерея — Ionicons `images-outline` (модалка смены фото). */
@Composable
fun GtGalleryMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        // Рамка снимка.
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.14f, size.height * 0.18f),
            size = Size(size.width * 0.72f, size.height * 0.64f),
            cornerRadius = CornerRadius(stroke.width * 2),
            style = stroke,
        )
        // Солнце.
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.07f,
            center = Offset(size.width * 0.36f, size.height * 0.38f),
            style = stroke,
        )
        // Гора.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.2f, size.height * 0.76f),
            end = Offset(size.width * 0.44f, size.height * 0.5f),
            strokeWidth = stroke.width,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.44f, size.height * 0.5f),
            end = Offset(size.width * 0.8f, size.height * 0.76f),
            strokeWidth = stroke.width,
        )
    }
}

/** Лупа — Ionicons `search` (поиск дашборда). */
@Composable
fun GtSearchMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.28f,
            center = Offset(size.width * 0.42f, size.height * 0.42f),
            style = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.64f, size.height * 0.64f),
            end = Offset(size.width * 0.86f, size.height * 0.86f),
            strokeWidth = stroke.width,
        )
    }
}

/** Крест — Ionicons `close`/`close-circle` (очистка поиска). */
@Composable
fun GtCloseMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = (Spacing.xxs / 4).toPx()
        drawLine(
            color = tint,
            start = Offset(size.width * 0.25f, size.height * 0.25f),
            end = Offset(size.width * 0.75f, size.height * 0.75f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.75f, size.height * 0.25f),
            end = Offset(size.width * 0.25f, size.height * 0.75f),
            strokeWidth = stroke,
        )
    }
}

/** Плюс — Ionicons `add` (FAB и кнопка пустого состояния). */
@Composable
fun GtPlusMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = (Spacing.xxs / 4).toPx()
        drawLine(
            color = tint,
            start = Offset(size.width * 0.5f, size.height * 0.2f),
            end = Offset(size.width * 0.5f, size.height * 0.8f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.2f, size.height * 0.5f),
            end = Offset(size.width * 0.8f, size.height * 0.5f),
            strokeWidth = stroke,
        )
    }
}

/** Воронка — Ionicons `funnel-outline` (пустой результат фильтра). */
@Composable
fun GtFunnelMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = (Spacing.xxs / 4).toPx()
        // Верхняя кромка и стенки конуса.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.15f, size.height * 0.2f),
            end = Offset(size.width * 0.85f, size.height * 0.2f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.15f, size.height * 0.2f),
            end = Offset(size.width * 0.45f, size.height * 0.55f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.85f, size.height * 0.2f),
            end = Offset(size.width * 0.55f, size.height * 0.55f),
            strokeWidth = stroke,
        )
        // Носик.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.45f, size.height * 0.55f),
            end = Offset(size.width * 0.45f, size.height * 0.8f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.55f, size.height * 0.55f),
            end = Offset(size.width * 0.55f, size.height * 0.8f),
            strokeWidth = stroke,
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.45f, size.height * 0.8f),
            end = Offset(size.width * 0.55f, size.height * 0.8f),
            strokeWidth = stroke,
        )
    }
}

/** Облако с чертой — Ionicons `cloud-offline-outline` (баннер ≥5 минут). */
@Composable
fun GtCloudOffMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        // Капсула-основание облака.
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.18f, size.height * 0.52f),
            size = Size(size.width * 0.64f, size.height * 0.3f),
            cornerRadius = CornerRadius(size.height * 0.15f),
            style = stroke,
        )
        // Верхняя дуга-бугор.
        drawArc(
            color = tint,
            startAngle = -180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.28f, size.height * 0.22f),
            size = Size(size.width * 0.44f, size.height * 0.6f),
            style = stroke,
        )
        // Диагональная черта.
        drawLine(
            color = tint,
            start = Offset(size.width * 0.12f, size.height * 0.88f),
            end = Offset(size.width * 0.88f, size.height * 0.12f),
            strokeWidth = stroke.width,
        )
    }
}

/** Список — Ionicons `list` (переключатель вида: list). */
@Composable
fun GtListViewMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = (Spacing.xxs / 4).toPx()
        for (index in 0..2) {
            val y = size.height * (0.25f + index * 0.25f)
            drawLine(
                color = tint,
                start = Offset(size.width * 0.15f, y),
                end = Offset(size.width * 0.85f, y),
                strokeWidth = stroke,
            )
        }
    }
}

/** Карточки — Ionicons `albums` (переключатель вида: card). */
@Composable
fun GtCardViewMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = Stroke(width = (Spacing.xxs / 4).toPx())
        // Задняя карточка.
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.32f, size.height * 0.12f),
            size = Size(size.width * 0.54f, size.height * 0.54f),
            cornerRadius = CornerRadius(stroke.width * 2),
            style = stroke,
        )
        // Передняя карточка.
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.14f, size.height * 0.34f),
            size = Size(size.width * 0.54f, size.height * 0.54f),
            cornerRadius = CornerRadius(stroke.width * 2),
            style = stroke,
        )
    }
}

/** Сетка — Ionicons `grid` (переключатель вида: grid, 3×3 точки). */
@Composable
fun GtGridViewMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        for (row in 0..2) {
            for (column in 0..2) {
                drawCircle(
                    color = tint,
                    radius = size.minDimension * 0.08f,
                    center = Offset(
                        size.width * (0.2f + column * 0.3f),
                        size.height * (0.2f + row * 0.3f),
                    ),
                )
            }
        }
    }
}
