package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key

/**
 * Обёртка всего контента приложения (Stage 6 п.4, VAL-I18N-004/005): подставляет
 * локаль из preference (null = системная, RN-дефолт) и пересоздаёт дерево по key
 * при её смене — подписи, включая плюральные формы, перекомпоновываются без рестарта.
 *
 * Рецепт документации CMP «Manage local resource environment»; платформенную
 * работу делает [LocalAppLocale.provides] (androidMain — конфигурация контекста,
 * jvmMain — Locale.setDefault).
 *
 * В App() значение приходит из AppSettings (`greenthumb_language`) реактивно:
 * `AppEnvironment(customAppLocale = settings.language.wire)`. Первые кадры до
 * чтения DataStore рендерятся с системной локалью — тот же settle-паттерн, что
 * у темы и интро-флага.
 */
@Composable
public fun AppEnvironment(
    customAppLocale: String?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalAppLocale provides customAppLocale,
    ) {
        key(customAppLocale) {
            content()
        }
    }
}
