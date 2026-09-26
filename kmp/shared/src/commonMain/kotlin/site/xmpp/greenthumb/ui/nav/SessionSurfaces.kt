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
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Поверхности сессии ВНЕ графа маршрутов (Stage 6 п.1–2): гейт загрузки и
 * KMP-состояния Stage 3, у которых нет RN-маршрута. Тексты — RU-заглушки до
 * kmp-i18n-resources (ключи «ключ не найден» добавит эта фича) и Stage 7
 * screen-login (кнопки ввода ключа / создания аккаунта).
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
 * установка — обновление поверх предыдущей. Объяснение (аккаунт на сервере /
 * ключ ввести руками / восстановить нельзя → создать новый); кнопки —
 * Stage 7 (screen-login).
 */
@Composable
public fun GtKeyNotFoundScreen() {
    SessionSurface {
        Text(
            text = "Аккаунт не найден на устройстве. " +
                "Ваш аккаунт хранится на сервере — введите ключ восстановления вручную " +
                "или создайте новый аккаунт (старый ключ восстановить нельзя).",
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
