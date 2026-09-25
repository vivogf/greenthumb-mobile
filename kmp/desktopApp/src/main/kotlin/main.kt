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
import site.xmpp.greenthumb.data.AccountPlantGate
import site.xmpp.greenthumb.data.accountGate

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
    Window(
        onCloseRequest = {
            connectivity.close()
            graph.client.close()
            exitApplication()
        },
        title = "GreenThumb",
    ) {
        App(graph.manager, connectivity, plants)
    }
}
