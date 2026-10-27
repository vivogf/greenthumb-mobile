package com.greenthumbplantcare.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import com.greenthumbplantcare.GreenThumbApplication
import com.greenthumbplantcare.MainActivity
import com.greenthumbplantcare.R
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import site.xmpp.greenthumb.core.storage.AppLanguage
import site.xmpp.greenthumb.data.buildWidgetUiNow
import site.xmpp.greenthumb.data.widgetLanguage
import site.xmpp.greenthumb.season.TzLatitudesStore

/**
 * Home-screen widget (architecture §5.8): due and overdue plants of the signed-in account, read from
 * its local database only. The composition keeps observing the source, so changes made while it is
 * alive show without another update; [WidgetUpdates] and [WidgetRefreshWorker] start new ones.
 */
class PlantsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as GreenThumbApplication
        val snapshots = app.widgetSource.observe().flowOn(Dispatchers.IO)
        val initial = snapshots.first()
        val latitudes = TzLatitudesStore.loadOrEmpty()
        provideContent {
            val snapshot by snapshots.collectAsState(initial)
            val ui = buildWidgetUiNow(snapshot, latitudes)
            val texts = remember(snapshot.language) { widgetTexts(context, snapshot.language) }
            PlantsWidgetContent(
                ui = ui,
                texts = texts,
                openPlant = { plantId -> actionStartActivity(plantIntent(context, plantId)) },
                openApp = actionStartActivity(Intent(context, MainActivity::class.java)),
            )
        }
    }
}

/**
 * Data URI of a row's launch intent. PendingIntents differ by intent identity, and extras are not part
 * of it: one URI per plant keeps every row opening its own plant.
 */
fun widgetPlantUri(plantId: String): String =
    "greenthumb-widget://plant/" + URLEncoder.encode(plantId, "UTF-8")

/** MainActivity reads `plant_id` on a cold start and in onNewIntent (same path as a push tap). */
internal fun plantIntent(context: Context, plantId: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        data = Uri.parse(widgetPlantUri(plantId))
        putExtra(MainActivity.EXTRA_PLANT_ID, plantId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

/** Texts in the in-app language, or the device language when none is saved. */
internal fun widgetTexts(context: Context, saved: AppLanguage?): WidgetTexts {
    val device = Resources.getSystem().configuration.locales[0].language
    val language = widgetLanguage(saved, device)
    val configuration = Configuration(context.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(language.wire))
    }
    val res = context.createConfigurationContext(configuration).resources
    return WidgetTexts(
        title = res.getString(R.string.widget_title),
        allWatered = res.getString(R.string.widget_all_watered),
        noPlants = res.getString(R.string.widget_no_plants),
        signedOut = res.getString(R.string.widget_signed_out),
        dueToday = res.getString(R.string.widget_due_today),
        overdue = { days -> res.getString(R.string.widget_overdue, days) },
        more = { count -> res.getString(R.string.widget_more, count) },
    )
}
