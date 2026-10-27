package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.intl.Locale
import site.xmpp.greenthumb.core.storage.AppLanguage

/**
 * Language the UI is shown in. Nothing saved means "follow the system locale", and the string
 * resources then resolve ru for a Russian locale and fall back to en (values/) for any other.
 */
public fun effectiveAppLanguage(saved: AppLanguage?, systemLanguage: String): AppLanguage {
    if (saved != null) return saved
    val code = systemLanguage.substringBefore('-').substringBefore('_').lowercase()
    return if (code == AppLanguage.Ru.wire) AppLanguage.Ru else AppLanguage.En
}

/** [effectiveAppLanguage] for the locale the Compose string resources currently read. */
@Composable
public fun effectiveAppLanguage(saved: AppLanguage?): AppLanguage =
    effectiveAppLanguage(saved, Locale.current.language)
