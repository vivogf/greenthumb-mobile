package site.xmpp.greenthumb.ui.components

import androidx.compose.ui.window.DialogProperties

internal actual fun gtModalDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false)
