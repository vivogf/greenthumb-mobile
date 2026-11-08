package site.xmpp.greenthumb.ui.components

import androidx.compose.material3.CalendarLocale

/**
 * Locale for the Material3 calendar (month and weekday names) from the app language. Without it the
 * calendar takes the phone language, even when the app runs in another one.
 */
internal expect fun calendarLocaleOf(languageTag: String): CalendarLocale
