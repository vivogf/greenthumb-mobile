package com.greenthumbplantcare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import site.xmpp.greenthumb.App
import site.xmpp.greenthumb.SessionGraph
import site.xmpp.greenthumb.core.platform.Connectivity
import site.xmpp.greenthumb.core.storage.AppSettings
import site.xmpp.greenthumb.core.storage.LegacyHandoff
import site.xmpp.greenthumb.core.storage.PlantDatabases
import site.xmpp.greenthumb.core.storage.SecureStore
import site.xmpp.greenthumb.core.storage.registerAppContext
import site.xmpp.greenthumb.data.AccountPlantGate
import site.xmpp.greenthumb.data.accountGate

class MainActivity : ComponentActivity() {

    private lateinit var connectivity: Connectivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        // Контекст для isUpdateInstall() (Stage 3 п.6): чтение firstInstallTime/
        // lastUpdateTime пакета до первого запроса сессии.
        registerAppContext(appContext)
        lateinit var plants: AccountPlantGate
        val graph = SessionGraph.create(
            secure = SecureStore(appContext),
            settings = AppSettings(appContext),
            handoff = LegacyHandoff(appContext),
            deleteUserDatabase = { userId -> plants.closeAndDelete(userId) },
        )
        plants = PlantDatabases(appContext).accountGate(graph.api, graph.accountSession)
        connectivity = Connectivity(appContext)
        setContent {
            App(graph.manager, connectivity, plants)
        }
    }

    override fun onDestroy() {
        // onDestroy может прийти, если onCreate не дошёл до присваивания
        // (редкий крэш конструктора). close идемпотентен, но lateinit — нет.
        if (::connectivity.isInitialized) {
            connectivity.close()
        }
        super.onDestroy()
    }
}
