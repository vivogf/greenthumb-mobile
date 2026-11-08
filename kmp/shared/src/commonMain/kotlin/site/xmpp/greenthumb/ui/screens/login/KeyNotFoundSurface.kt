package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.ui.components.GtKeyMark
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.SecondaryButton
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.session_keyNotFoundBody
import site.xmpp.greenthumb.ui.res.session_keyNotFoundCreateAccount
import site.xmpp.greenthumb.ui.res.session_keyNotFoundEnterKey
import site.xmpp.greenthumb.ui.res.session_keyNotFoundTitle
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Экран «ключ не найден» (SessionState.KeyNotFound → StartRoute.KeyNotFound,
 * Stage 3 п.6, VAL-HANDOFF-IMP-002): RU-заглушка App.kt (kmp-key-not-found-
 * screen) заменена локализованной поверхностью с КНОПКАМИ (пин фичи
 * screen-login):
 *
 * - заголовок/текст — i18n-ключи `session_keyNotFound*` (обе локали):
 *   аккаунт хранится на сервере; ключ ввести вручную; восстановить нельзя
 *   → создать новый;
 * - «ввести ключ» → режим login логина ([LoginKeyMode] через [LoginScreen]);
 * - «создать аккаунт» → режим create ([CreateMode] → шлюз показа ключа).
 *
 * Поверхность ВНЕ графа маршрутов (сессии нет — маршрут один); тап по кнопке
 * ведёт в ветку логина ([site.xmpp.greenthumb.App] решает переход: KeyNotFound
 * → Login, сохраняя факт «обновление» в SessionManager — экран «не найден»
 * после этого действия пользователя больше не показывается, см.
 * signedOutOrKeyNotFound/hadSession).
 */
@Composable
public fun KeyNotFoundSurface(
    onEnterLogin: () -> Unit,
    onEnterCreate: () -> Unit,
    onSignInWithSavedKey: () -> Unit = {},
    notice: SavedKeyOutcome? = null,
    busy: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    // Same card, header and scroll as the sign-in modes (M19): long text at font 1.3 scrolls, not clips.
    LoginCard {
        LoginHeader(
            icon = { GtKeyMark(tint = scheme.secondary, modifier = it) },
            title = stringResource(Res.string.session_keyNotFoundTitle),
            subtitle = stringResource(Res.string.session_keyNotFoundBody),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            PrimaryButton(
                text = stringResource(Res.string.session_keyNotFoundEnterKey),
                onClick = onEnterLogin,
                modifier = Modifier.gtButtonWidth(),
            )
            SavedKeySignInBlock(onClick = onSignInWithSavedKey, outcome = notice, enabled = !busy)
            SecondaryButton(
                text = stringResource(Res.string.session_keyNotFoundCreateAccount),
                onClick = onEnterCreate,
                modifier = Modifier.gtButtonWidth(),
            )
        }
    }
}
