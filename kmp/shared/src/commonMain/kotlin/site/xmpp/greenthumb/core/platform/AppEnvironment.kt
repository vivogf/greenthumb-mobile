package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Подстановка локали всего контента приложения (Stage 6 п.4, VAL-I18N-004/005):
 * пишет preference (null = системная, RN-дефолт) в платформенное состояние и
 * в CompositionLocal. Рецепт документации CMP «Manage local resource
 * environment»; платформенную работу делает [LocalAppLocale.provides]
 * (androidMain — конфигурация контекста, jvmMain — Locale.setDefault).
 *
 * БЕЗ key(customAppLocale) (исправление VAL-I18N-006, архитектура §9):
 * раньше обёртка пересоздавала всё поддерево по key() при смене языка — вместе
 * с ним и NavHost (rememberNavController + back stack внутри) — и выбранная
 * вкладка сбрасывалась на стартовый маршрут, а сессия переигрывалась. Теперь
 * контент перекомпоновывается локальным ключом [AppLocalizedContent]
 * (GtAppNavGraph): строка ресурсов перечитывается от [LocalAppLocale] на
 * каждый проход (ComposeEnvironment.rememberEnvironment читает Locale.current
 * во время композиции), навигационное состояние и сессия живут вне ключа.
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
        content()
    }
}

/**
 * Локализованное поддерево (Stage 6 п.5, VAL-I18N-006): единственный key() на
 * смену языка в приложении. Ключ = значение локали [current] (строка, которую
 * читают строки ресурсов), поэтому «локаль не пересчитана» не убивает узлы.
 * В [GtAppNavGraph] ключом оборачивается контент маршрутов + панель вкладок —
 * локализованный UI; сам NavHost и rememberNavController живут СНАРУЖИ, чтобы
 * смена языка сохраняла маршрут/стек (RN-паритет: смена языка в профиле
 * редиректа не делает).
 */
@Composable
public fun AppLocalizedContent(content: @Composable () -> Unit) {
    val locale = LocalAppLocale.current
    androidx.compose.runtime.key(locale) {
        content()
    }
}
