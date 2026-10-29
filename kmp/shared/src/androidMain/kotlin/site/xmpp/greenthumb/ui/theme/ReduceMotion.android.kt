package site.xmpp.greenthumb.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun systemReduceMotion(): Boolean {
    val resolver = LocalContext.current.applicationContext.contentResolver
    var reduce by remember(resolver) { mutableStateOf(readReduceMotion(resolver)) }
    // The scales change in Settings while the app keeps running; nothing else reports it.
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = readReduceMotion(resolver)
            }
        }
        for (key in listOf(Settings.Global.ANIMATOR_DURATION_SCALE, Settings.Global.TRANSITION_ANIMATION_SCALE)) {
            resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
        }
        reduce = readReduceMotion(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduce
}

private fun readReduceMotion(resolver: ContentResolver): Boolean = isReduceMotion(
    animatorDurationScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f),
    transitionAnimationScale = Settings.Global.getFloat(resolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f),
)
