package site.xmpp.greenthumb.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import site.xmpp.greenthumb.App
import site.xmpp.greenthumb.SessionGraph
import site.xmpp.greenthumb.core.platform.Connectivity
import site.xmpp.greenthumb.core.storage.AppSettings
import site.xmpp.greenthumb.core.storage.LegacyHandoff
import site.xmpp.greenthumb.core.storage.PlantDatabases
import site.xmpp.greenthumb.core.storage.SecureStore
import site.xmpp.greenthumb.data.openerFor

fun main() = application {
    val graph = SessionGraph.create(
        secure = SecureStore(Any()),
        settings = AppSettings(Any()),
        handoff = LegacyHandoff(Any()),
    )
    // jvm-actual: всегда онлайн — у харнесса источника состояния сети нет.
    val connectivity = Connectivity(Any())
    val opener = PlantDatabases(Any()).openerFor(graph.api)
    Window(
        onCloseRequest = {
            connectivity.close()
            graph.client.close()
            exitApplication()
        },
        title = "GreenThumb",
    ) {
        App(graph.manager, connectivity, opener)
    }
}
