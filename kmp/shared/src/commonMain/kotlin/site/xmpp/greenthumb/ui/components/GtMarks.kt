package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.gt_ic_back
import site.xmpp.greenthumb.ui.res.gt_ic_cal
import site.xmpp.greenthumb.ui.res.gt_ic_camera
import site.xmpp.greenthumb.ui.res.gt_ic_check
import site.xmpp.greenthumb.ui.res.gt_ic_chev
import site.xmpp.greenthumb.ui.res.gt_ic_close
import site.xmpp.greenthumb.ui.res.gt_ic_diag
import site.xmpp.greenthumb.ui.res.gt_ic_drop
import site.xmpp.greenthumb.ui.res.gt_ic_drop_filled
import site.xmpp.greenthumb.ui.res.gt_ic_edit
import site.xmpp.greenthumb.ui.res.gt_ic_fert
import site.xmpp.greenthumb.ui.res.gt_ic_journal
import site.xmpp.greenthumb.ui.res.gt_ic_leaf
import site.xmpp.greenthumb.ui.res.gt_ic_loc
import site.xmpp.greenthumb.ui.res.gt_ic_lock
import site.xmpp.greenthumb.ui.res.gt_ic_photo
import site.xmpp.greenthumb.ui.res.gt_ic_plus
import site.xmpp.greenthumb.ui.res.gt_ic_pot
import site.xmpp.greenthumb.ui.res.gt_ic_scissors
import site.xmpp.greenthumb.ui.res.gt_ic_smartcam
import site.xmpp.greenthumb.ui.res.gt_ic_species
import site.xmpp.greenthumb.ui.res.gt_ic_sun

/**
 * Метки интерфейса в стиле Retro-cosmos (NASA-punk, D§3.4): линия 1,5 из 24, прямые концы,
 * острые углы, без заливки. [tint] приходит из темы, здесь цвет не собирается.
 *
 * Метки с глифом в макете v2 рисуют векторный ресурс `gt_ic_*` (экспорт
 * `kmp/scripts/icons/export_mockup_icons.mjs`); остальные — Canvas с той же линией ([markStroke]).
 */

private const val MarkStrokeRatio = 1.5f / 24f

/** Толщина линии Canvas-метки: та же доля размера, что 1,5 в 24-единичном ресурсе. */
internal fun DrawScope.markStrokeWidth(): Float = size.minDimension * MarkStrokeRatio

internal fun DrawScope.markStroke(): Stroke =
    Stroke(width = markStrokeWidth(), cap = StrokeCap.Butt, join = StrokeJoin.Miter)

@Composable
private fun GtResourceMark(icon: DrawableResource, tint: Color, modifier: Modifier) {
    Icon(painter = painterResource(icon), contentDescription = null, tint = tint, modifier = modifier)
}

@Composable
fun GtCalendarMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_cal, tint, modifier)

/** Куда смотрит [GtChevronMark]. Глиф `gt_ic_chev` смотрит вправо; [degrees] — поворот от него. */
enum class GtChevronDirection(internal val degrees: Float) {
    Down(90f),
    Left(180f),
    Up(270f),
    Right(0f),
}

/** [GtChevronDirection.Left] — отдельный глиф «Назад» (`gt_ic_back`), остальные — повёрнутый `gt_ic_chev`. */
@Composable
fun GtChevronMark(
    tint: Color,
    modifier: Modifier = Modifier,
    direction: GtChevronDirection = GtChevronDirection.Down,
) {
    if (direction == GtChevronDirection.Left) {
        GtResourceMark(Res.drawable.gt_ic_back, tint, modifier)
    } else {
        GtResourceMark(Res.drawable.gt_ic_chev, tint, modifier.graphicsLayer { rotationZ = direction.degrees })
    }
}

@Composable
fun GtCameraMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_camera, tint, modifier)

@Composable
fun GtLeafMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_leaf, tint, modifier)

/** Капля полива; [filled] — сплошная капля (кнопка полива), по умолчанию контур с бликом. */
@Composable
fun GtWaterDropMark(tint: Color, modifier: Modifier = Modifier, filled: Boolean = false) =
    GtResourceMark(if (filled) Res.drawable.gt_ic_drop_filled else Res.drawable.gt_ic_drop, tint, modifier)

@Composable
fun GtLockMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_lock, tint, modifier)

/** Галочка «готово». */
@Composable
fun GtCheckMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_check, tint, modifier)

@Composable
fun GtSunMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_sun, tint, modifier)

@Composable
fun GtPencilMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_edit, tint, modifier)

@Composable
fun GtLocationMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_loc, tint, modifier)

@Composable
fun GtPotMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_pot, tint, modifier)

@Composable
fun GtScissorsMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_scissors, tint, modifier)

@Composable
fun GtGalleryMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_photo, tint, modifier)

@Composable
fun GtCloseMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_close, tint, modifier)

@Composable
fun GtPlusMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_plus, tint, modifier)

/** Подкормка (карточка ухода). */
@Composable
fun GtFertilizeMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_fert, tint, modifier)

/** Умная камера (кнопка дашборда, заголовок умной камеры). */
@Composable
fun GtSmartCameraMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_smartcam, tint, modifier)

/** «Что с растением?» — лист под лупой. */
@Composable
fun GtDiagnosisMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_diag, tint, modifier)

/** Вид растения (карточка вида, «Узнать вид по фото»). */
@Composable
fun GtSpeciesMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_species, tint, modifier)

/** Журнал растения (заголовок раздела). */
@Composable
fun GtJournalMark(tint: Color, modifier: Modifier = Modifier) =
    GtResourceMark(Res.drawable.gt_ic_journal, tint, modifier)

// ---------------------------------------------------------------------------
// Canvas-метки без глифа в макете: та же линия ([markStroke]).
// ---------------------------------------------------------------------------
/** Метка вкладки «Профиль» — Ionicons `person` тем же простым стилем (голова + плечи). */
@Composable
fun GtPersonMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = markStroke()
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

/** Колокольчик напоминаний — Ionicons `notifications` (слайд 3 welcome). */
@Composable
fun GtBellMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = markStroke()
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
        val stroke = markStrokeWidth()
        drawCircle(
            color = tint,
            radius = size.minDimension * 0.14f,
            center = Offset(size.width * 0.38f, size.height * 0.3f),
            style = markStroke(),
        )
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.12f, size.height * 0.48f),
            size = Size(size.width * 0.52f, size.height * 0.44f),
            style = markStroke(),
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
        val stroke = markStroke()
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

/** Предупреждение — Ionicons `warning` (треугольник с восклицательным знаком). */
@Composable
fun GtWarningMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = markStroke()
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

/** Галочка без круга: состояние «выполнено» внутри кнопки, где кольцо дало бы двойную рамку. */
@Composable
fun GtTickMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = markStrokeWidth()
        val elbow = Offset(size.width * 0.4f, size.height * 0.72f)
        drawLine(tint, Offset(size.width * 0.14f, size.height * 0.5f), elbow, stroke)
        drawLine(tint, elbow, Offset(size.width * 0.86f, size.height * 0.24f), stroke)
    }
}

/** Копирование — Ionicons `copy-outline` (два скруглённых прямоугольника). */
@Composable
fun GtCopyMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = markStroke()
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
        val stroke = markStroke()
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
        val stroke = markStroke()
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
        val stroke = markStroke()
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
        val stroke = markStroke()
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
        val stroke = markStroke()
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
        val stroke = markStroke()
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
        val stroke = markStroke()
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
        val stroke = markStroke()
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

/** Луна — Ionicons `moon-outline` (пикер темы: dark). */
@Composable
fun GtMoonMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = markStroke()
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

/** Шестерёнка — Ionicons `settings-outline` (кнопка настроек ухода). */
@Composable
fun GtGearMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = markStroke()
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
        val stroke = markStroke()
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

/** Лупа — Ionicons `search` (поиск дашборда). */
@Composable
fun GtSearchMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = markStroke()
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

/** Воронка — Ionicons `funnel-outline` (пустой результат фильтра). */
@Composable
fun GtFunnelMark(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = markStrokeWidth()
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
        val stroke = markStroke()
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
        val stroke = markStrokeWidth()
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
        val stroke = markStroke()
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
