package site.xmpp.greenthumb.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

internal actual fun gtModalDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false)

@Composable
internal actual fun GtModalClearWindowDim() = Unit
