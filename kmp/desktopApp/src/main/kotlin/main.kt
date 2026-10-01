package site.xmpp.greenthumb.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import site.xmpp.greenthumb.App
import site.xmpp.greenthumb.SessionGraph
import site.xmpp.greenthumb.core.platform.Connectivity
import site.xmpp.greenthumb.core.storage.AppSettings
import site.xmpp.greenthumb.core.storage.LegacyHandoff
import site.xmpp.greenthumb.core.storage.PlantDatabases
import site.xmpp.greenthumb.core.storage.SecureStore
import site.xmpp.greenthumb.data.AccountPlantGate
import site.xmpp.greenthumb.data.accountGate
import site.xmpp.greenthumb.ui.screens.gallery.ComponentGalleryScreen
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

fun main() = application {
    lateinit var plants: AccountPlantGate
    val graph = SessionGraph.create(
        secure = SecureStore(Any()),
        settings = AppSettings(Any()),
        handoff = LegacyHandoff(Any()),
        deleteUserDatabase = { userId -> plants.closeAndDelete(userId) },
    )
    plants = PlantDatabases(Any()).accountGate(graph.api, graph.accountSession)
    // jvm-actual: всегда онлайн — у харнесса источника состояния сети нет.
    val connectivity = Connectivity(Any())
    if (System.getenv("GT_GALLERY") == "1") {
        // Дев-поверхность Stage 5 вне приложения: галерея компонентов M5
        // (GT_GALLERY=1 при hotRun). Приложение (Stage 6) — ветка else.
        Window(onCloseRequest = { exitApplication() }, title = "GreenThumb — component gallery") {
            var darkTheme by remember { mutableStateOf(false) }
            GreenThumbTheme(darkTheme = darkTheme) {
                ComponentGalleryScreen(
                    darkTheme = darkTheme,
                    onDarkThemeChange = { darkTheme = it },
                    onClose = { exitApplication() },
                )
            }
        }
    } else {
        Window(
            onCloseRequest = {
                connectivity.close()
                graph.client.close()
                exitApplication()
            },
            title = "GreenThumb",
        ) {
            App(graph.manager, connectivity, plants, graph.settings, graph.push, killSwitch = graph.killSwitch)
        }
    }
}
