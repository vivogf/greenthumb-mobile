package site.xmpp.greenthumb.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemGesturesPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.AppLocalizedContent
import site.xmpp.greenthumb.core.platform.PushTokens
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.borderHairline
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.login_accountCreated
import site.xmpp.greenthumb.ui.res.login_keySaved
import site.xmpp.greenthumb.ui.res.login_saveKeyNow
import site.xmpp.greenthumb.ui.res.login_yourRecoveryKey
import site.xmpp.greenthumb.ui.screens.enablenotifications.EnableNotificationsScreen
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/**
 * Поверхность окна ПОСЛЕ входа (Stage 7 п.2–3, RN-подавление `login.tsx:41-45`):
 * [ShowKey] — шлюз показа ключа (интерим до screen-login, который заменит его
 * полноценным режимом show-key с копированием и предупреждениями), [Enable] —
 * pre-permission промпт `auth/enable-notifications` (Stage 7 п.2). Состояние
 * живёт в App() как remember — переживает пересоздание NavHost'а при смене
 * состояния сессии (вход меняет state → граф пересоздаётся), не переживает
 * смерть процесса (RN-паритет: mode в useState терялся так же).
 */
public sealed class PostSignInSurface {
    /** Шлюз «ключ показан один раз» (RN mode='show-key', VAL-LOGIN-002-подавление). */
    public data class ShowKey(public val recoveryKey: String) : PostSignInSurface()

    /** Pre-permission промпт (RN `router.replace('/(auth)/enable-notifications')`). */
    public data object Enable : PostSignInSurface()
}

/**
 * Хост окна после входа: show-key → enable-notifications → дашборд (RN-цепочка
 * create → show-key → continue → enable-notifications → goHome). Поверхность
 * локализуется тем же паттерном, что маршруты графа — [AppLocalizedContent]
 * (remember-состояние экрана не должно теряться при смене языка — правило
 * parity-файла для Stage 7 экранов).
 *
 * Переход show-key → enable внутри хоста (RN `handleContinue` =
 * `router.replace('/(auth)/enable-notifications')`): [onDone] завершает
 * только окно целиком (после enable-экрана — на дашборд).
 */
@Composable
public fun PostSignInHost(
    surface: PostSignInSurface,
    push: PushTokens,
    pushLanguage: String,
    onDone: () -> Unit,
) {
    var current by remember { mutableStateOf(surface) }
    AppLocalizedContent {
        when (val s = current) {
            is PostSignInSurface.ShowKey ->
                InterimShowKeyScreen(
                    recoveryKey = s.recoveryKey,
                    onContinue = { current = PostSignInSurface.Enable },
                )
            PostSignInSurface.Enable ->
                EnableNotificationsScreen(
                    push = push,
                    language = pushLanguage,
                    onFinished = onDone,
                )
        }
    }
}

/**
 * ИНТЕРИМ-шлюз show-key (заменяется целиком фичей screen-login): после
 * [SessionManager.createAnonymousAccount] ключ показан ровно один раз, кнопка
 * «я сохранил» — единственный выход на следующий шаг (RN login.tsx:88:
 * router.replace на enable-notifications). Копирование и предупреждения
 * «безвозвратность» — полноценный режим screen-login; здесь минимальный
 * показ, чтобы шлюз существовал и enable-notifications был достижим только
 * после него (VAL-INTRO-003).
 */
@Composable
private fun InterimShowKeyScreen(
    recoveryKey: String,
    onContinue: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .systemGesturesPadding()
            .padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surface, RoundedCornerShape(Radii.xl))
                .borderHairline(greenThumbExtendedColors().cardBorder, RoundedCornerShape(Radii.xl))
                .padding(Spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(
                text = stringResource(Res.string.login_accountCreated),
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.login_saveKeyNow),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(Res.string.login_yourRecoveryKey),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
            )
            // Ключ моноширинно; wrap — ключ длинный, горизонтального скролла
            // быть не должно (полный show-key screen-login добавит копирование).
            Text(
                text = recoveryKey,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surfaceVariant, RoundedCornerShape(Radii.md))
                    .padding(Spacing.md),
            )
            PrimaryButton(
                text = stringResource(Res.string.login_keySaved),
                onClick = onContinue,
                modifier = Modifier.gtButtonWidth(),
            )
        }
    }
}
