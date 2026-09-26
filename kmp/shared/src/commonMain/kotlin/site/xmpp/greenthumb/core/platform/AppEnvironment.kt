package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Подстановка локали всего контента приложения (Stage 6 п.4, VAL-I18N-004/005):
 * пишет preference (null = системная, RN-дефолт) в платформенное состояние и
 * в CompositionLocal. Платформенную работу делает [LocalAppLocale.provides]
 * (androidMain — детерминированный слой [AppLocale]: выбранный язык вписан в
 * Configuration/LocaleList каждой доставки конфигурации
 * MainActivity.onCreate/onConfigurationChanged — ВНЕ Compose-прохода, M6b
 * VAL-I18N-007; jvmMain — Locale.setDefault).
 *
 * БЕЗ key(customAppLocale) (исправление VAL-I18N-006, архитектура §9):
 * раньше обёртка пересоздавала всё поддерево по key() при смене языка — вместе
 * с ним и NavHost (rememberNavController + back stack внутри) — и выбранная
 * вкладка сбрасывалась на стартовый маршрут, а сессия переигрывалась. Теперь
 * контент перекомпоновывается локальным ключом [AppLocalizedContent]
 * (GtAppNavGraph): строка ресурсов перечитывается от remembered-окружения
 * (DefaultComposeEnvironment: remember от Locale.current) на каждый проход,
 * навигационное состояние и сессия живут вне ключа.
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
 *
 * Синхронизация локали перед key() (M6b, VAL-I18N-007): каждая свежая
 * композиция локализованного поддерева (remount вкладки, смена сцены)
 * сначала переприменяет выбранную локаль к доставленной конфигурации
 * ([AppLocaleSyncRoot], android; desktop — no-op). Это закрывает последнее
 * окно гонки, которое не покрывается activity-хуками: финальная ступень
 * последовательности uiMode-флипов (restore-доставка) мутирует локали
 * процесса ПОСЛЕ всех колбэков и не порождает рекомпозицию (Compose видит
 * diff=0) — без этой синхронизации свежий remember-окружение строк
 * собиралось от сброшенного системой LocaleList (красная проба: подписи EN
 * при Language: ru на remount). Тело composable выполняется до композиции
 * детей — remembered-окружение строк собирается уже от исправленного
 * состояния.
 */
@Composable
public fun AppLocalizedContent(content: @Composable () -> Unit) {
    AppLocaleSyncRoot()
    val locale = LocalAppLocale.current
    androidx.compose.runtime.key(locale) {
        content()
    }
}
