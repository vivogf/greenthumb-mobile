package com.greenthumbplantcare

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.floor
import site.xmpp.greenthumb.App
import site.xmpp.greenthumb.core.platform.AppActivityHolder
import site.xmpp.greenthumb.core.platform.AppLocale
import site.xmpp.greenthumb.core.platform.Connectivity

class MainActivity : ComponentActivity() {

    private lateinit var connectivity: Connectivity

    /**
     * Pending `data.plant_id` launch-intent'а (deep-link пуша, Stage 9 п.7):
     * наблюдаемое состояние — App() читает параметром и потребляет через
     * [onLaunchPlantIdConsumed] (переход plant/{id} выполняется ТОЛЬКО после
     * SignedIn — без сессии extra не открывает данные и не обходит вход).
     */
    private var launchPlantId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Activity для activity-result'ов пикера фото (Stage 8 п.1): registry
        // живёт на Activity, Application-контекста недостаточно. Пишется до
        // setContent — pickImage вызывается уже из первого кадра экранов.
        AppActivityHolder.activity = this
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
        // Deep-link пуша (Stage 9 п.7): plant_id читается при ХОЛОДНОМ старте
        // (тап по FCM-пуши убитого приложения — система кладёт data в extras
        // launch-intent'а). Пересоздание конфигурацией (savedInstanceState !=
        // null) тот же intent НЕ перечитывает: id уже потреблён (или ещё ждёт
        // входа в состоянии) — повторная навигация не повторяется.
        if (savedInstanceState == null) {
            launchPlantId = extractPlantId(intent)
        }
        setContent {
            App(
                app.sessionGraph.manager,
                connectivity,
                app.plantGate,
                app.sessionGraph.settings,
                app.sessionGraph.push,
                launchPlantId = launchPlantId,
                onLaunchPlantIdConsumed = { launchPlantId = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Тёплый старт (launchMode singleTask — паритет RN-активности): тап по
        // пушу при живом процессе доставляет intent сюда. Intent без plant_id
        // (обычный запуск иконкой) pending id не сбрасывает — отложенный
        // переход остаётся в силе.
        extractPlantId(intent)?.let { launchPlantId = it }
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
        AppActivityHolder.activity = null
        super.onDestroy()
    }

    /**
     * `data.plant_id` из полезной нагрузки (Stage 9 п.7): FCM кладёт data в
     * extras launch-intent'а строками; контракт RN — number|string
     * (`app/_layout.tsx:52`), поэтому числовые extras тоже принимаются
     * (синтетические запуски `am start --ei`). Прочие extras и отсутствие —
     * null (локальное тестовое уведомление extras не несёт — маршрутизации нет).
     */
    private fun extractPlantId(intent: Intent?): String? {
        val extras = intent?.extras ?: return null
        return when (val value = extras.get(EXTRA_PLANT_ID)) {
            is String -> value.trim().takeIf { it.isNotEmpty() }
            is Number -> numberToPlantId(value)
            else -> null
        }
    }

    /** Числовые id — как у RN-шаблона `/plant/${number}` без хвоста `.0`. */
    private fun numberToPlantId(value: Number): String = when (value) {
        is Int, is Long, is Short, is Byte -> value.toLong().toString()
        is Double, is Float -> {
            val d = value.toDouble()
            if (d.isFinite() && d == floor(d)) d.toLong().toString() else d.toString()
        }
        else -> value.toString()
    }

    private companion object {
        /** Ключ полезной нагрузки пуша (backend `data.plant_id`, RN-паритет). */
        const val EXTRA_PLANT_ID = "plant_id"
    }
}
