package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Keeps the platform window (its background and system bars) in the in-app theme's page colour,
 * so a gap between screens never shows the other theme. Desktop: no-op.
 */
@Composable
public expect fun SyncWindowColors(page: Color, dark: Boolean)

/** The dark flag last applied to the window, readable before the settings store loads; null if none. */
@Composable
public expect fun lastWindowDark(): Boolean?
