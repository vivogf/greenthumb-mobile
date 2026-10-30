package site.xmpp.greenthumb.ui.components

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

// Without decorFitsSystemWindows = false the dialog window pans instead of resizing, so the
// keyboard covers the card's buttons and imePadding() never sees the IME.
internal actual fun gtModalDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)

// The dialog theme dims the window behind it (about 60 % black) on top of the scrim GtModal draws,
// which turned the Light backdrop near-black. GtModal's own scrim is the only dim layer.
@Composable
internal actual fun GtModalClearWindowDim() {
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setDimAmount(0f)
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }
}
