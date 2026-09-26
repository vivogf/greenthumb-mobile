package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
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
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = scheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Text(
                    text = stringResource(Res.string.session_keyNotFoundTitle),
                    style = MaterialTheme.typography.headlineSmall,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.session_keyNotFoundBody),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                PrimaryButton(
                    text = stringResource(Res.string.session_keyNotFoundEnterKey),
                    onClick = onEnterLogin,
                    modifier = Modifier.gtButtonWidth(),
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                SecondaryButton(
                    text = stringResource(Res.string.session_keyNotFoundCreateAccount),
                    onClick = onEnterCreate,
                    modifier = Modifier.gtButtonWidth(),
                )
            }
        }
    }
}
