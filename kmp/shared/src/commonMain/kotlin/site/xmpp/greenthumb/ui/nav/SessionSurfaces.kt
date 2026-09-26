package site.xmpp.greenthumb.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.session_keyNotFoundBody
import site.xmpp.greenthumb.ui.res.session_keyNotFoundTitle
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Поверхности сессии ВНЕ графа маршрутов (Stage 6 п.1–2): гейт загрузки и
 * KMP-состояния Stage 3, у которых нет RN-маршрута. Тексты — Compose Resources
 * (Stage 6 п.4); ключи «ключ не найден» — KMP-специфичные (в RN этого экрана
 * нет). Кнопки экрана («ввести ключ» / «создать аккаунт», ресурсы
 * `session_keyNotFound*` уже готовы) — Stage 7 (screen-login).
 */

/**
 * Гейт стартовой маршрутизации (RN `app/index.tsx`): индикатор на фоне
 * сплеша, БЕЗ редиректа — пока сессия не решена или флаг интро не прочитан.
 */
@Composable
public fun GtSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Экран «ключ не найден» (Stage 3 п.6, VAL-HANDOFF-IMP-002): ключа нет нигде,
 * установка — обновление поверх предыдущей. Заголовок и текст — ресурсы
 * `session_keyNotFound*` (обе локали); объяснение: аккаунт на сервере / ключ
 * ввести руками / восстановить нельзя → создать новый. Кнопки — Stage 7.
 */
@Composable
public fun GtKeyNotFoundScreen() {
    SessionSurface {
        Text(
            text = stringResource(Res.string.session_keyNotFoundTitle),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = stringResource(Res.string.session_keyNotFoundBody),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Провал импорта handoff (VAL-HANDOFF-IMP-005): файл остался на диске,
 * пользователю показывается ключ для копирования. Экран ошибки — Stage 7.
 */
@Composable
public fun GtHandoffImportFailedScreen(recoveryKey: String) {
    SessionSurface {
        Text(
            text = "Handoff import failed. Recovery key to copy:\n$recoveryKey",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Общий каркас поверхностей ошибки сессии: фон + центрированный текст. */
@Composable
private fun SessionSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}
