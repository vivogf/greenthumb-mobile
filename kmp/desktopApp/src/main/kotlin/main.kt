package site.xmpp.greenthumb.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import site.xmpp.greenthumb.App

fun main() = application {
    Window(
        onCloseRequest = { exitApplication() },
        title = "GreenThumb",
    ) {
        App()
    }
}
