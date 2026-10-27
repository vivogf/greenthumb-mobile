package com.greenthumbplantcare.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class PlantsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PlantsWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.schedule(context)
        WidgetUpdates.start(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // KEEP: a widget that survives an app update or reboot keeps the one existing job.
        WidgetRefreshWorker.schedule(context)
        WidgetUpdates.start(context)
    }

    override fun onDisabled(context: Context) {
        WidgetRefreshWorker.cancel(context)
        WidgetUpdates.stop()
        super.onDisabled(context)
    }
}
