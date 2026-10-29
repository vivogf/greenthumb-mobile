package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import site.xmpp.greenthumb.core.storage.ThemePreference

/**
 * Keeps the platform window (its background and system bars) in the in-app theme's page colour,
 * so a gap between screens never shows the other theme. Desktop: no-op.
 */
@Composable
public expect fun SyncWindowColors(page: Color, dark: Boolean)

/** The dark flag last applied to the window, readable before the settings store loads; null if none. */
@Composable
public expect fun lastWindowDark(): Boolean?

/** Night mode the platform should use for this app outside Compose (the system splash window). */
public enum class AppNightMode { FollowSystem, Light, Dark }

public fun appNightMode(preference: ThemePreference?): AppNightMode = when (preference) {
    ThemePreference.Light -> AppNightMode.Light
    ThemePreference.Dark -> AppNightMode.Dark
    ThemePreference.Auto, null -> AppNightMode.FollowSystem
}

/**
 * Tells the platform the in-app Light/Dark/Auto choice, so the next cold start's system splash
 * already has the chosen page colour. Android 12+ only; older Android and desktop: no-op.
 */
@Composable
public expect fun SyncAppNightMode(mode: AppNightMode)
