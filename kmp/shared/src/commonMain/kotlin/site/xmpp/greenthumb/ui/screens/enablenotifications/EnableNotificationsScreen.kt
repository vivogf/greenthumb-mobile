package site.xmpp.greenthumb.ui.screens.enablenotifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemGesturesPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.PushOutcome
import site.xmpp.greenthumb.core.platform.PushTokens
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.components.GtBellMark
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.borderHairline
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.common_error
import site.xmpp.greenthumb.ui.res.enableNotifications_body
import site.xmpp.greenthumb.ui.res.enableNotifications_enable
import site.xmpp.greenthumb.ui.res.enableNotifications_later
import site.xmpp.greenthumb.ui.res.enableNotifications_title
import site.xmpp.greenthumb.ui.res.profile_pushPermissionDenied
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/**
 * Экран «включить уведомления» (Stage 7 п.2, фича screen-enable-notifications,
 * VAL-INTRO-002) — порт `app/(auth)/enable-notifications.tsx` (169 строк,
 * прочитан целиком):
 *
 * - pre-permission промпт ПОСЛЕ show-key: объясняет ценность напоминаний до
 *   системного диалога разрешения;
 * - Enable: один suspend-шаг push-подсистемы [PushTokens.requestSubscribe]
 *   (RN: registerForPushNotificationsAsync + subscribeToExpoNotifications).
 *   Subscribed → дашборд; Denied → подсказка «как включить позже» (RN
 *   showAlert(pushPermissionDenied)) + дашборд; Error → сообщение сбоя +
 *   дашборд. Во время шага обе кнопки disabled (RN `disabled={loading}`).
 *   Повторный Enable возможен: RN пускал повтор после finally-сброса loading;
 * - Later: сразу дашборд (RN handleLater), без модалки;
 * - модалка подсказки (RN useAlertDialog): RN-алерт — глобальный оверлей,
 *   переживает goHome (дашборд под модалкой); KMP-модалка здесь живёт в
 *   составе экрана, поэтому навигация отложена до закрытия модалки
 *   ([onFinished] из кнопки OK / tap-outside) — пользователь видит ту же
 *   последовательность «подсказка → дашборд», порядок шагов другой
 *   (осознанное отличие, parity-файл).
 *
 * Отличия от RN-исходника, осознанные по parity-файлу:
 * - `SafeAreaView` → statusBars/navigationBars/systemGestures-пэддинги (как у
 *   [site.xmpp.greenthumb.ui.screens.welcome.WelcomeScreen]);
 * - Ionicons `notifications` → Canvas-метка [GtBellMark] (material-icons в
 *   пинах миссии нет, прецедент GtBottomTabs); круг RN 80/радиус 40 и иконка
 *   40 — ближайшие шаги шкалы без литералов;
 * - `colors.primary + '22'` → `primaryContainer` (тот же приём, что у
 *   welcome-слайдов и GtEmptyState);
 * - RN-гаптика (Haptics.notificationAsync Success / selectionAsync) не
 *   переносится: Haptics — expect-слой M10, на desktop no-op; эффект тапа не
 *   влияет на навигацию или данные;
 * - RN-подсказка отказа различала iOS/Android (pushPermissionDeniedIOS);
 *   KMP-мишень миссии — только Android (desktop — заглушка [PushOutcome.Denied]):
 *   используется общая строка `profile_pushPermissionDenied`. iOS-ветка
 *   вернётся при появлении таргета;
 * - спиннер RN жил внутри строки CTA; здесь кнопка гасится (disabled) и
 *   спиннер — под карточкой: форма PrimaryButton (labelLarge) не меняется.
 */
@Composable
public fun EnableNotificationsScreen(
    push: PushTokens,
    language: String,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val title = stringResource(Res.string.enableNotifications_title)
    val body = stringResource(Res.string.enableNotifications_body)
    val enableLabel = stringResource(Res.string.enableNotifications_enable)
    val laterLabel = stringResource(Res.string.enableNotifications_later)
    val errorTitle = stringResource(Res.string.common_error)
    val permissionHint = stringResource(Res.string.profile_pushPermissionDenied)

    // Подсказка при отказе/сбое (RN showAlert(t('common.error'), …)).
    // RN-алерт — глобальный оверлей, переживает goHome; KMP-модалка в составе
    // экрана, поэтому закрытие модалки завершает окно (onFinished) — см. KDoc.
    if (errorText != null) {
        GtAlertDialog(
            title = errorTitle,
            message = errorText,
            buttons = listOf(GtAlertButton(text = "OK")),
            onDismissRequest = {
                errorText = null
                onFinished()
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .systemGesturesPadding()
            // RN: View justifyContent center + padding 24 → [Spacing.xxl].
            .padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
    ) {
        // Карточка RN: borderRadius 16 (Radii.xl), padding 24 (Spacing.xxl),
        // gap 20 (Spacing.xl).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surface, RoundedCornerShape(Radii.xl))
                .borderHairline(greenThumbExtendedColors().cardBorder, RoundedCornerShape(Radii.xl))
                .padding(Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            // Иконка + заголовок + текст (RN column center, gap 14 → [Spacing.md]).
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                // Круг 80×80 (RN) с колоколом.
                Box(
                    modifier = Modifier
                        .size(Spacing.xxl * 2 + Spacing.xxs)
                        .background(scheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    GtBellMark(
                        tint = scheme.primary,
                        modifier = Modifier.size(Spacing.xxl * 2 - Spacing.xxs),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            // Primary CTA (RN paddingVertical 14, radius 10 → PrimaryButton).
            PrimaryButton(
                text = enableLabel,
                onClick = {
                    if (loading) return@PrimaryButton
                    loading = true
                    errorText = null
                    scope.launch {
                        // RN-ветки enable: модалка подсказки/сбоя при не-granted
                        // исходе, дашборд после её закрытия (RN-алерт переживал
                        // goHome — здесь onFinished из onDismissRequest, см. KDoc).
                        when (val outcome = push.requestSubscribe(language)) {
                            is PushOutcome.Subscribed -> {
                                errorText = null
                                onFinished()
                            }
                            is PushOutcome.Denied -> {
                                errorText = permissionHint
                                // onFinished при закрытии модалки (onDismissRequest).
                            }
                            is PushOutcome.Error -> {
                                errorText = outcome.message
                                // onFinished при закрытии модалки (onDismissRequest).
                            }
                        }
                        loading = false
                    }
                },
                enabled = !loading,
                modifier = Modifier.gtButtonWidth(),
            )

            // Secondary CTA (RN: текст mutedForeground, по центру,
            // paddingVertical 8 → [Spacing.sm]; disabled на время Enable-шага).
            Text(
                text = laterLabel,
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        enabled = !loading,
                    ) { onFinished() }
                    .padding(vertical = Spacing.sm),
            )
        }

        // RN показывал ActivityIndicator внутри CTA на время шага; здесь
        // состояние видно disabled-CTA, спиннер — под карточкой.
        if (loading) {
            Spacer(modifier = Modifier.height(Spacing.xl))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = scheme.primary,
                    modifier = Modifier.size(Spacing.xxl * 2 - Spacing.xxs),
                )
            }
        }
    }
}
