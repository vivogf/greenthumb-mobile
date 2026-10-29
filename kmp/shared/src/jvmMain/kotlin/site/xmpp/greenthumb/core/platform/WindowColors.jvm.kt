package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
public actual fun SyncWindowColors(page: Color, dark: Boolean) = Unit

@Composable
public actual fun lastWindowDark(): Boolean? = null
