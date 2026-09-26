package site.xmpp.greenthumb.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import site.xmpp.greenthumb.core.platform.AppLocalizedContent
import site.xmpp.greenthumb.core.platform.PushTokens
import site.xmpp.greenthumb.ui.screens.enablenotifications.EnableNotificationsScreen
import site.xmpp.greenthumb.ui.screens.login.ShowKeySurface

/**
 * Поверхность окна ПОСЛЕ входа (Stage 7 п.2–3, RN-подавление `login.tsx:41-45`):
 * [ShowKey] — шлюз показа ключа (полный порт show-key screen-login,
 * [site.xmpp.greenthumb.ui.screens.login.ShowKeySurface]: копирование,
 * предупреждения, единственный выход кнопкой «я сохранил»), [Enable] —
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
                ShowKeySurface(
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
