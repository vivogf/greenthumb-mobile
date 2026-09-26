package com.greenthumbplantcare

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import site.xmpp.greenthumb.App
import site.xmpp.greenthumb.core.platform.AppLocale
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
        // Выбранная локаль приложения — в доставленную конфигурацию ДО dispatch
        // в Compose (M6b VAL-I18N-007): onConfigurationChanged переприменяет её
        // на каждой доставке, Compose-проход конфигурацию не мутирует. Первый
        // вызов до чтения DataStore — no-op (выбор ещё null; при settle
        // provides() зафиксирует выбор — AppLocale.remember).
        AppLocale.applyToConfiguration(resources.configuration)
        setContent {
            App(
                app.sessionGraph.manager,
                connectivity,
                app.plantGate,
                app.sessionGraph.settings,
                app.sessionGraph.push,
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges=uiMode (Stage 6 п.3): система доставляет новую
        // конфигурацию без пересоздания Activity — её локаль снова системная.
        // Синхронно переприменяем выбранный язык к доставленной конфигурации
        // (M6b VAL-I18N-007) и — пост-проходом — ещё раз: последовательность
        // доставок uiMode-флипа многоступенчата (display-override ступени),
        // последняя мутация локали случается после возврата колбэка.
        // Окно после финальной restore-доставки (без колбэка и без
        // рекомпозиции) закрывает AppLocaleSyncRoot в композиции.
        AppLocale.applyToConfiguration(newConfig)
        Handler(Looper.getMainLooper()).post {
            AppLocale.applyToConfiguration(resources.configuration)
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
