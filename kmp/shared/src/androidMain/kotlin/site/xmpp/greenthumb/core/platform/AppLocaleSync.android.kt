package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Android actual: чтение LocalConfiguration делает корень наблюдаемым к
 * каждой доставке конфигурации; тело выполняется синхронно до детей.
 */
@Composable
public actual fun AppLocaleSyncRoot() {
    val configuration = LocalConfiguration.current
    // Синхронно (до композиции детей) переприменить выбранную локаль к
    // доставленной конфигурации и глобальному LocaleList — источник строк
    // (remembered-окружение DefaultComposeEnvironment) собирается от них.
    AppLocale.applyToConfiguration(configuration)
}
