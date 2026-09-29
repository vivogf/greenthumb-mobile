package site.xmpp.greenthumb.ui.screens.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemGesturesPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.Haptics
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.ui.components.GtBellMark
import site.xmpp.greenthumb.ui.components.GtLeafMark
import site.xmpp.greenthumb.ui.components.GtWaterDropMark
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.intro_getStarted
import site.xmpp.greenthumb.ui.res.intro_next
import site.xmpp.greenthumb.ui.res.intro_skip
import site.xmpp.greenthumb.ui.res.intro_slide1Body
import site.xmpp.greenthumb.ui.res.intro_slide1Title
import site.xmpp.greenthumb.ui.res.intro_slide2Body
import site.xmpp.greenthumb.ui.res.intro_slide2Title
import site.xmpp.greenthumb.ui.res.intro_slide3Body
import site.xmpp.greenthumb.ui.res.intro_slide3Title
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Экран интро/welcome (Stage 7 п.1, фича screen-welcome, VAL-INTRO-001) —
 * порт `app/(intro)/welcome.tsx` (181 строка, прочитана целиком):
 *
 * - три слайда (leaf/water/notifications) с заголовком и текстом из ресурсов
 *   `intro_slide*` (RN `intro.slide1Title` и т.д.);
 * - Skip справа сверху завершает сразу;
 * - горизонтальная пейдж-карусель (RN `ScrollView pagingEnabled` +
 *   `onMomentumScrollEnd` → [HorizontalPager]; свайп синхронизирует dots
 *   и подпись кнопки, анимация страницы — платформенная);
 * - dots: активный — широкая пилюля primary (RN 24×8), остальные — точки
 *   `colors.border` (RN 8×8);
 * - CTA снизу: Next до последнего слайда, Get started на последнем;
 * - завершение: [AppPreferencesStore.setIntroSeen] (RN `setHasSeenIntro`)
 *   один раз + [onFinish] один раз (RN `router.replace('/(auth)/login')` —
 *   переход решает граф навигации).
 *
 * Отличия от RN-исходника, осознанные по parity-файлу:
 * - `SafeAreaView` → `statusBarsPadding`/`navigationBarsPadding`/`systemGesturesPadding`
 *   (инсеты решает Compose, не safe-area-библиотека);
 * - `Ionicons leaf/water/notifications` → Canvas-метки [GtLeafMark] /
 *   [GtWaterDropMark] / [GtBellMark] (material-icons в пинах миссии нет,
 *   прецедент GtBottomTabs);
 * - `colors.primary + '22'` (альфа-хвост строки) → `primaryContainer`
 *   (тот же приём, что у [site.xmpp.greenthumb.ui.components.GtEmptyState]:
 *   rgba-строку в токены не заводят);
 * - RN-гаптика тапов перенесена (M10, VAL-ANIM-004): Next → [Haptics.selection],
 *   завершение на последнем слайде → [Haptics.light] (RN selectionAsync /
 *   impactAsync(Light)); Skip в RN гаптики не имеет — здесь тоже; тактильный
 *   отклик не меняет навигацию или данные (fire-and-forget, desktop no-op).
 *
 * Завершение — только кнопками (Skip / Get started), свайп карусели интро
 * не закрывает — как в RN.
 */
@Composable
fun WelcomeScreen(
    settings: AppPreferencesStore,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { SLIDE_COUNT })
    // Эквивалент RN-навигации: второй replace не создаёт новый стек; двойной
    // тап по завершившей кнопке не зовёт второй onFinish (тест: idempotent).
    var finished by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val onLast = pagerState.currentPage == SLIDE_LAST
    val ctaLabel = stringResource(if (onLast) Res.string.intro_getStarted else Res.string.intro_next)
    val skipLabel = stringResource(Res.string.intro_skip)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .systemGesturesPadding(),
    ) {
        // Skip — справа сверху (RN paddingHorizontal 20, paddingTop 8, hitSlop
        // → внутренний padding 8 = Spacing.xs).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = skipLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) {
                        if (!finished) {
                            finished = true
                            scope.launch { settings.setIntroSeen(); onFinish() }
                        }
                    }
                    .padding(Spacing.xs),
            )
        }

        // Карусель: три полных страницы (RN ScrollView pagingEnabled);
        // свайп двигает слайды сам — RN-состояние index синхронизировал
        // onMomentumScrollEnd, пейджер делает это источником истины.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            Slide(page = page)
        }

        // Dots (RN: row center, gap 8 → padding horizontal 4, активная 24×8).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.lg),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(SLIDE_COUNT) { index ->
                val active = index == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = Spacing.xxs)
                        .size(width = if (active) Dp(Spacing.xl.value + Spacing.xs.value) else Spacing.xs, height = Spacing.xs)
                        .background(if (active) scheme.primary else scheme.outline, CircleShape),
                )
            }
        }

        // CTA (RN paddingHorizontal 20, paddingBottom 20, radius 10 = Radii.md
        // внутри PrimaryButton; paddingVertical 14 = Spacing.md — тоже там).
        PrimaryButton(
            text = ctaLabel,
            onClick = {
                if (onLast) {
                    if (!finished) {
                        finished = true
                        // RN impactAsync(Light) на завершении (welcome.tsx:50).
                        Haptics.light()
                        scope.launch { settings.setIntroSeen(); onFinish() }
                    }
                } else {
                    scope.launch {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                    // RN selectionAsync() после перехода (welcome.tsx:57).
                    Haptics.selection()
                }
            },
            modifier = Modifier
                .gtButtonWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
        )
    }
}

/** Число слайдов интро — 1:1 с RN SLIDES (welcome.tsx). */
internal const val SLIDE_COUNT: Int = 3

/** Индекс последнего слайда. */
internal const val SLIDE_LAST: Int = SLIDE_COUNT - 1

/** Слайд карусели: метка в круге + заголовок + текст (RN-центрирование). */
@Composable
private fun Slide(page: Int) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xxl + Spacing.xs),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Круг с иконкой (RN 120×120, радиус 60; `primary + '22'` → primaryContainer).
        Box(
            modifier = Modifier
                .size(Spacing.xxl * 5)
                .background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SlideMark(
                page = page,
                tint = scheme.primary,
                iconSide = Spacing.xxl * 2 + Spacing.xs,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.xxl))
        Text(
            text = slideTitle(page),
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Spacing.xxl))
        Text(
            text = slideBody(page),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            // RN maxWidth 320 → ближайшая шкала (без литерала в экране).
            modifier = Modifier.width(Spacing.xxl * 13 + Spacing.xs),
        )
    }
}

/** Метка слайда: leaf / water / notifications (RN Ionicons). */
@Composable
private fun SlideMark(page: Int, tint: androidx.compose.ui.graphics.Color, iconSide: Dp) {
    val mark = Modifier.size(iconSide)
    when (page) {
        0 -> GtLeafMark(tint = tint, modifier = mark)
        1 -> GtWaterDropMark(tint = tint, modifier = mark)
        else -> GtBellMark(tint = tint, modifier = mark)
    }
}

/** Заголовок слайда из ресурсов (локаль решает AppLocalizedContent). */
@Composable
private fun slideTitle(page: Int): String = when (page) {
    0 -> stringResource(Res.string.intro_slide1Title)
    1 -> stringResource(Res.string.intro_slide2Title)
    else -> stringResource(Res.string.intro_slide3Title)
}

/** Текст слайда из ресурсов. */
@Composable
private fun slideBody(page: Int): String = when (page) {
    0 -> stringResource(Res.string.intro_slide1Body)
    1 -> stringResource(Res.string.intro_slide2Body)
    else -> stringResource(Res.string.intro_slide3Body)
}
