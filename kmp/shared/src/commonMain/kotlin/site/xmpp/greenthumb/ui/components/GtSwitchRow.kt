package site.xmpp.greenthumb.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Переключатель (тумблер пушей профиля, RN `Switch` в
 * `app/(tabs)/profile.tsx`): дорожка 52×32 (RN 51×31 — ближайшие шаги шкалы
 * [Spacing]), бегунок 24 (RN 26). Цвета: выкл — muted-дорожка (surfaceVariant)
 * с mutedForeground-бегунком (onSurfaceVariant), вкл — primary-дорожка с
 * onPrimary-бегунком (RN `primary + '66'`/primary; альфа-хвост RN-строки в
 * токены не заводится — прецедент ShowKeySurface, контраст onPrimary/primary
 * читаем). Тап по дорожке меняет значение (RN onValueChange); disabled — тап
 * не работает (RN `disabled={pushToggling}`). [contentDescription] — a11y-имя
 * тумблера, входит в семантику узла (M11: фикс дефекта M9 — параметр
 * существовал, но в semantics не попадал; тест GtSwitchSemanticsTest).
 */
@Composable
fun GtSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    contentDescription: String,
) {
    val scheme = MaterialTheme.colorScheme
    val trackColor by animateColorAsState(
        targetValue = if (checked) scheme.primary else scheme.surfaceVariant,
        animationSpec = tween(durationMillis = 150),
        label = "switchTrack",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) scheme.onPrimary else scheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 150),
        label = "switchThumb",
    )
    Box(
        modifier = Modifier
            .size(width = SwitchWidth, height = SwitchHeight)
            .background(trackColor, RoundedCornerShape(percent = 50))
            .toggleable(
                value = checked,
                role = Role.Switch,
                enabled = enabled,
                onValueChange = onCheckedChange,
            )
            .semantics {
                this.selected = checked
                // M11 (дефект M9): переданный contentDescription теперь входит
                // в семантику — профиль передаёт `profile.notifications`
                // (a11y-имя тумблера пушей); поведение switch не меняется.
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = Spacing.xxs / 4)
                .size(width = SwitchThumbWidth, height = SwitchHeight - Spacing.xxs / 2)
                .background(thumbColor, CircleShape),
        )
    }
}

/** Ширина дорожки: RN Switch 51 → ближайший шаг шкалы 52. */
private val SwitchWidth = Spacing.xxl * 2 + Spacing.xs

/** Высота дорожки: RN Switch 31 → ближайший шаг шкалы 32. */
private val SwitchHeight = Spacing.xxl + Spacing.xxs

/** Ширина бегунка: RN 26 → шаг шкалы 24 ([Spacing.xxl]). */
private val SwitchThumbWidth = Spacing.xxl
