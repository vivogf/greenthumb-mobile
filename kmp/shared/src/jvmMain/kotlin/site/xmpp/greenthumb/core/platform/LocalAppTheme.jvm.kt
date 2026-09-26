package site.xmpp.greenthumb.core.platform

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

@Composable
public actual fun isSystemDarkTheme(): Boolean = isSystemInDarkTheme()
