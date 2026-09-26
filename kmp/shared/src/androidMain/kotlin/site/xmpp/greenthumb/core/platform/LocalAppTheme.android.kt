package site.xmpp.greenthumb.core.platform

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
public actual fun isSystemDarkTheme(): Boolean {
    val configuration = LocalConfiguration.current
    return (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
}
