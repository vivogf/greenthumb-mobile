package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * Desktop-актуал по рецепту документации CMP «Manage local resource environment»:
 * `Locale.setDefault` — дефолтную локаль JVM читают stringResource/pluralStringResource
 * на desktop (харнесс и UI-тесты).
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
