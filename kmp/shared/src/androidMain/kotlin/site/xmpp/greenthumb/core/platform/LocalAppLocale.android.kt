package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Android-актуал по рецепту документации CMP «Manage local resource environment»:
 * локаль пишется в конфигурацию активити (`Configuration.setLocale`) и в ресурсы
 * контекста (`resources.updateConfiguration`) — именно их читают
 * `stringResource`/`pluralStringResource` на Android.
 *
 * Наблюдаемость (исправление VAL-I18N-004/006, Android-поверхность — красные
 * доказательства user-testing m6 раунд 1): локаль публикуется ещё и
 * CompositionLocal'ом [LocalAppLocale] — как на jvm. Первый вариант актуала
 * возвращал `LocalConfiguration.provides(configuration)` — тот же экземпляр,
 * что уже предоставлен платформенной обвязкой: равные значения CompositionLocal
 * инвалидаций не дают, а `current` читал глобальный `Locale.getDefault()`, не
 * наблюдаемый Compose. Итог: `AppLocalizedContent` не перекомпоновывался,
 * `key(locale)` не пересчитывался, подписи вкладок оставались на старом языке
 * до перемонтирования вкладки (кнопка языка при этом менялась — она под
 * другим recompose-скоупом). Теперь смена значения локали инвалидирует
 * читателей `current`; перечитывание строк обеспечивают мутации конфигурации
 * выше + свежая композиция под ключом.
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
        val configuration = LocalConfiguration.current
        configuration.setLocale(new)
        val resources = LocalContext.current.resources

        resources.updateConfiguration(configuration, resources.displayMetrics)
        return LocalAppLocale.provides(new.toString())
    }
}
