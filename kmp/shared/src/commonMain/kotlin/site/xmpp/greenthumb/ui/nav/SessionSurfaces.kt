package site.xmpp.greenthumb.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import site.xmpp.greenthumb.ui.screens.login.KeyNotFoundSurface
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Поверхности сессии ВНЕ графа маршрутов (Stage 6 п.1–2): гейт загрузки и
 * KMP-состояния Stage 3, у которых нет RN-маршрута. Тексты — Compose Resources
 * (Stage 6 п.4); ключи «ключ не найден» — KMP-специфичные (в RN этого экрана
 * нет). Кнопки экрана («ввести ключ» / «создать аккаунт») — screen-login
 * (Stage 7 п.3): делегирует [KeyNotFoundSurface].
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
 * Экран «ключ не найден» (Stage 3 п.6 → Stage 7 п.3 screen-login,
 * VAL-HANDOFF-IMP-002): ключа нет нигде, установка — обновление поверх
 * предыдущей. Локализованный текст + КНОПКИ (RU-заглушка App.kt заменена
 * фичей screen-login): «ввести ключ» → режим login, «создать аккаунт» →
 * режим create. Точки решения — [site.xmpp.greenthumb.App] (KeyNotFound →
 * Login-ветка графа); SessionManager сохраняет признак «сессия жила» (см.
 * signedOutOrKeyNotFound), так что после тапа экран больше не возвращается.
 */
@Composable
public fun GtKeyNotFoundScreen(
    onEnterLogin: () -> Unit,
    onEnterCreate: () -> Unit,
) {
    KeyNotFoundSurface(onEnterLogin = onEnterLogin, onEnterCreate = onEnterCreate)
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
