package site.xmpp.greenthumb.ui.components

import androidx.compose.material3.CalendarLocale
import java.util.Locale

internal actual fun calendarLocaleOf(languageTag: String): CalendarLocale = Locale.forLanguageTag(uiLanguage(languageTag))
