package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * Android-актуал локали приложения (Stage 6 п.4 + M6b VAL-I18N-007).
 *
 * Наблюдаемость (исправление VAL-I18N-004/006, Android-поверхность — красные
 * доказательства user-testing m6 раунд 1): локаль публикуется
 * CompositionLocal'ом [LocalAppLocale]; смена значения инвалидирует читателей
 * `current` (AppLocalizedContent и его key(locale)).
 *
 * Платформенная синхронизация — СИНХРОННО, вне Compose-прохода (M6b): мутация
 * `Configuration.setLocale` + `resources.updateConfiguration` ВНУТРИ
 * `provides` удалена — она не наблюдаема Compose и стирается каждой доставкой
 * конфигурации (uiMode-переключения), а remembered-окружение строк Compose
 * Resources пересобирается от глобального LocaleList: свежая композиция
 * (ремаунт вкладки) могла прочитать системный EN при сохранённом ru
 * (интермиттент 2/5, evidence r2-anomaly-en-tabs-analysis.txt; тот же класс
 * дефекта, против которого architecture.md §9 предупреждает «не полагаться
 * только на одноразовую мутацию Resources в Compose-проходе»). С M6b:
 *  - [AppLocale.remember]/[AppLocale.applyToConfiguration] вписывают выбранную
 *    локаль в конфигурацию КАЖДОЙ доставки (MainActivity.onCreate/
 *    onConfigurationChanged, до dispatch в Compose);
 *  - здесь — только синхронный `Locale.setDefault` (путь переключения языка
 *    в рантайме без доставки конфигурации: свежая композиция после смены
 *    значения читает глобальную локаль детерминированно);
 *  - мутаций Configuration/Resources из композиции нет вовсе.
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
        // Детерминированный слой M6b: remember — фиксация выбора для будущих
        // доставок конфигурации; setDefault — немедленная видимость выбранного
        // языка в свежих композициях после смены языка (конфигурация каждой
        // доставки уже несёт этот выбор — MainActivity).
        AppLocale.remember(new)
        Locale.setDefault(new)

        return LocalAppLocale.provides(new.toString())
    }
}
