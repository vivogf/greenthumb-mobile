package com.greenthumbplantcare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import site.xmpp.greenthumb.App
import site.xmpp.greenthumb.SessionGraph
import site.xmpp.greenthumb.core.storage.AppSettings
import site.xmpp.greenthumb.core.storage.LegacyHandoff
import site.xmpp.greenthumb.core.storage.SecureStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        val graph = SessionGraph.create(
            secure = SecureStore(appContext),
            settings = AppSettings(appContext),
            handoff = LegacyHandoff(appContext),
        )
        setContent {
            App(graph.manager)
        }
    }
}
