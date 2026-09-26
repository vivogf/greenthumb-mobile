package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * Desktop-актуал локали приложения (Stage 6 п.4 + M6b VAL-I18N-007).
 *
 * Ресурсы desktop (харнесс и UI-тесты) читают дефолтную локаль JVM:
 * remembered ResourceEnvironment собирается от Locale.current →
 * Locale.getDefault(). Синхронный `Locale.setDefault` в [provides] — источник
 * языка строк desktop, как и в M6: переключение языка сразу видно свежим
 * композициям, глобального состояния конфигураций (доставок) на desktop нет —
 * аномалии Android здесь не бывает.
 *
 * Наблюдаемость (исправление VAL-I18N-004/006): локаль публикуется
 * CompositionLocal'ом [LocalAppLocale]; смена значения инвалидирует читателей
 * `current` (AppLocalizedContent и его key(locale)) — перекомпоновка подписей
 * при смене языка не опирается на глобальные мутации.
 */
public actual object LocalAppLocale {
    private var default: Locale? = null
    private val LocalAppLocale = staticCompositionLocalOf { Locale.getDefault().toString() }

    public actual val current: String
        @Composable get() = LocalAppLocale.current

    @Composable
    public actual infix fun provides(value: String?): ProvidedValue<*> {
        if (default == null) {
            default = Locale.getDefault()
        }
        val new = when (value) {
            null -> default!!
            else -> Locale.forLanguageTag(value)
        }
        Locale.setDefault(new)
        return LocalAppLocale.provides(new.toString())
    }
}
