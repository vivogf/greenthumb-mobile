package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Рамка в 1 шаг шкалы / 4. На шкале Spacing нет 1, отдельный токен не заводим
 * (шкала закрыта в Stage 5 п.1–3). Выражение от [Spacing.xxs], не сырой литерал.
 */
internal fun Modifier.borderHairline(color: Color, shape: Shape): Modifier =
    border(width = Spacing.xxs / 4, color = color, shape = shape)
