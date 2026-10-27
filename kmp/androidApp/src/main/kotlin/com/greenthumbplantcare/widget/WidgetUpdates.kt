package com.greenthumbplantcare.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.greenthumbplantcare.GreenThumbApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * Redraws placed widgets after each change of the widget source (account, language, the account's
 * plants), in this process, without the periodic job. Runs only while at least one widget is placed.
 */
object WidgetUpdates {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun startIfPlaced(context: Context) {
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, PlantsWidgetReceiver::class.java))
        if (ids.isNotEmpty()) start(context)
    }

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext as GreenThumbApplication
        job = scope.launch {
            app.widgetSource.observe()
                .retryWhen { cause, _ ->
                    if (cause is CancellationException) return@retryWhen false
                    Log.w(TAG, "widget source failed, retrying", cause)
                    delay(RETRY_DELAY_MS)
                    true
                }
                .drop(1)
                .collectLatest {
                    try {
                        PlantsWidget().updateAll(app)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        Log.w(TAG, "widget update failed", error)
                    }
                }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private const val TAG = "GtWidget"
    private const val RETRY_DELAY_MS = 5_000L
}
