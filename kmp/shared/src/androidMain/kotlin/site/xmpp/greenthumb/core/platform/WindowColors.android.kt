package site.xmpp.greenthumb.core.platform

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import site.xmpp.greenthumb.ui.theme.GreenThumbExtendedColors

@Composable
public actual fun lastWindowDark(): Boolean? {
    val context = LocalContext.current
    return remember { WindowColors.savedDark(context) }
}

@Composable
public actual fun SyncWindowColors(page: Color, dark: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    val pageArgb = page.toArgb()
    SideEffect { WindowColors.apply(activity, pageArgb, dark) }
}

/**
 * Android 12+ draws the starting window from the app's own night mode, so `values-v31` (light page)
 * or `values-night-v31` (dark page) matches the in-app choice. FollowSystem clears the override,
 * so Auto keeps tracking the system and the app configuration reports the system mode again.
 */
@Composable
public actual fun SyncAppNightMode(mode: AppNightMode) {
    if (Build.VERSION.SDK_INT < 31) return
    val context = LocalContext.current
    LaunchedEffect(mode) {
        val uiModeManager = context.getSystemService(UiModeManager::class.java) ?: return@LaunchedEffect
        uiModeManager.setApplicationNightMode(
            when (mode) {
                AppNightMode.Light -> UiModeManager.MODE_NIGHT_NO
                AppNightMode.Dark -> UiModeManager.MODE_NIGHT_YES
                AppNightMode.FollowSystem -> UiModeManager.MODE_NIGHT_AUTO
            },
        )
    }
}
/**
 * The manifest theme paints the dark page before Compose starts. The last in-app choice is kept
 * in SharedPreferences (read synchronously in onCreate, unlike DataStore), so a Light user's
 * window turns light before the first frame.
 */
public object WindowColors {
    private const val PREFS = "gt_window"
    private const val KEY_DARK = "dark"

    public fun applySaved(activity: Activity) {
        val dark = prefs(activity).getBoolean(KEY_DARK, true)
        val page = if (dark) GreenThumbExtendedColors.dark.page else GreenThumbExtendedColors.light.page
        paint(activity, page.toArgb(), dark)
    }

    internal fun savedDark(context: Context): Boolean? =
        prefs(context).takeIf { it.contains(KEY_DARK) }?.getBoolean(KEY_DARK, true)

    internal fun apply(activity: Activity, pageArgb: Int, dark: Boolean) {
        paint(activity, pageArgb, dark)
        val prefs = prefs(activity)
        if (!prefs.contains(KEY_DARK) || prefs.getBoolean(KEY_DARK, true) != dark) {
            prefs.edit().putBoolean(KEY_DARK, dark).apply()
        }
    }

    private fun paint(activity: Activity, pageArgb: Int, dark: Boolean) {
        val window = activity.window
        window.setBackgroundDrawable(ColorDrawable(pageArgb))
        // From API 35 the app is edge-to-edge and the bar colours are ignored.
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.statusBarColor = pageArgb
            @Suppress("DEPRECATION")
            window.navigationBarColor = pageArgb
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
