package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable

/**
 * Desktop actual: доставок конфигурации нет; глобальная локаль обновляется в
 * LocalAppLocale.provides (LocalAppLocale.jvm) — хук пуст.
 */
@Composable
public actual fun AppLocaleSyncRoot() {
}
