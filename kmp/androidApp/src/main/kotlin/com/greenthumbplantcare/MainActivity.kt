package com.greenthumbplantcare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import site.xmpp.greenthumb.App
import site.xmpp.greenthumb.core.platform.Connectivity

class MainActivity : ComponentActivity() {

    private lateinit var connectivity: Connectivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Граф сессии и opener per-user баз — на процесс (Application, фикс M6
        // VAL-SHELL-003): пересоздание Activity конфигурацией (поворот, масштаб
        // шрифта) переиспользует их вместо повторного открытия DataStore-файлов
        // того же процесса (второй инстанс — IllegalStateException).
        val app = application as GreenThumbApplication
        // Connectivity — ресурс Activity (architecture.md §9): колбэк сети
        // снимается при уничтожении экрана, поэтому создаётся здесь, а не в
        // Application.
        connectivity = Connectivity(applicationContext)
        setContent {
            App(app.sessionGraph.manager, connectivity, app.plantGate, app.sessionGraph.settings)
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
