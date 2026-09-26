package site.xmpp.greenthumb.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import site.xmpp.greenthumb.ui.components.GtLeafMark
import site.xmpp.greenthumb.ui.components.GtPersonMark
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Высота панели вкладок — RN `app/(tabs)/_layout.tsx` (`height: 60`). В шкале
 * Spacing (4..24) нет 60: это высота контейнера, не отступ, отдельный токен
 * не заводится (та же логика, что у hairline в ui.components/Hairline.kt).
 */
private val TabBarHeight = 60.dp

/** Зазор иконка-подпись — RN `marginTop: 2` (в шкале нет — волосяной зазор). */
private val LabelGap = 2.dp

/** Сторона метки иконки: дефолт expo-router-таба ~24. */
private val TabIconSize = 24.dp

/**
 * Вкладки нижней навигации (Stage 6 п.1) — порт `app/(tabs)/_layout.tsx`:
 * две вкладки («Растения» — leaf, «Профиль» — person). Стиль по токенам:
 * фон `colors.card` (слот surface), верхняя граница `colors.border` (слот
 * outline) в 1 px, вертикальные отступы 4 ([Spacing.xxs]), активный tint
 * `colors.primary`, неактивный `colors.mutedForeground` (слот
 * onSurfaceVariant), подпись 11/500 (typography.labelSmall).
 *
 * Подписи — EN-литералы до фичи kmp-i18n-resources (та же фича перенесёт
 * `nav.plants`/`nav.profile` в Compose Resources; RN `t('nav.plants')`).
 * Материал-icons в пинах миссии нет — метки рисуются ([GtLeafMark]/[GtPersonMark]),
 * tint приходит из темы, здесь цвет не собирается.
 */
public enum class GtTab(public val route: String, public val title: String) {
    Plants(NavRoutes.TABS_DASHBOARD, "Plants"),
    Profile(NavRoutes.TABS_PROFILE, "Profile"),
}

@Composable
public fun GtBottomTabs(
    selected: GtTab,
    onSelect: (GtTab) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().height(TabBarHeight)) {
        // borderTopColor: colors.border, borderTopWidth: 1 (hairline = Spacing.xxs/4).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.xxs / 4)
                .background(scheme.outline),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            GtTab.entries.forEach { tab ->
                val tint = if (tab == selected) scheme.primary else scheme.onSurfaceVariant
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(
                            selected = tab == selected,
                            role = Role.Tab,
                            onClick = { onSelect(tab) },
                        )
                        .padding(vertical = Spacing.xxs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (tab) {
                        GtTab.Plants -> GtLeafMark(tint = tint, modifier = Modifier.size(TabIconSize))
                        GtTab.Profile -> GtPersonMark(tint = tint, modifier = Modifier.size(TabIconSize))
                    }
                    Spacer(modifier = Modifier.height(LabelGap))
                    Text(
                        text = tab.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                }
            }
        }
    }
}
