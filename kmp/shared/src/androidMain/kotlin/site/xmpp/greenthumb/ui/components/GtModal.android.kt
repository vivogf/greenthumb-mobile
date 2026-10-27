package site.xmpp.greenthumb.ui.components

import androidx.compose.ui.window.DialogProperties

// Without decorFitsSystemWindows = false the dialog window pans instead of resizing, so the
// keyboard covers the card's buttons and imePadding() never sees the IME.
internal actual fun gtModalDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
