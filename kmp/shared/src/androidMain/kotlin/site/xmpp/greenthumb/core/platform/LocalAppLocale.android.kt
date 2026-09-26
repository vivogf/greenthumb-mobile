package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Android-актуал по рецепту документации CMP «Manage local resource environment»:
 * локаль пишется в конфигурацию активити (`Configuration.setLocale`) и в ресурсы
 * контекста (`resources.updateConfiguration`) — именно их читают
 * `stringResource`/`pluralStringResource` на Android.
 */
public actual object LocalAppLocale {
    private var default: Locale? = null

    public actual val current: String
        @Composable get() = Locale.getDefault().toString()

    @Composable
    public actual infix fun provides(value: String?): ProvidedValue<*> {
        val configuration = LocalConfiguration.current

        if (default == null) {
            default = Locale.getDefault()
        }

        val new = when (value) {
            null -> default!!
            else -> Locale.forLanguageTag(value)
        }
        Locale.setDefault(new)
        configuration.setLocale(new)
        val resources = LocalContext.current.resources

        resources.updateConfiguration(configuration, resources.displayMetrics)
        return LocalConfiguration.provides(configuration)
    }
}
