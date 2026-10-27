package com.greenthumbplantcare.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import site.xmpp.greenthumb.data.WidgetRow
import site.xmpp.greenthumb.data.WidgetUi

/** Localized widget texts, resolved before composition so the content stays a pure function of its input. */
data class WidgetTexts(
    val title: String,
    val allWatered: String,
    val noPlants: String,
    val signedOut: String,
    val dueToday: String,
    val overdue: (Int) -> String,
    val more: (Int) -> String,
)

private val background = ColorProvider(day = Color(0xFFF3F7EF), night = Color(0xFF1E2A1F))
private val primaryText = ColorProvider(day = Color(0xFF1C1B1F), night = Color(0xFFE6E1D6))
private val secondaryText = ColorProvider(day = Color(0xFF55605A), night = Color(0xFFB7C2B8))
private val accent = ColorProvider(day = Color(0xFF2E7D32), night = Color(0xFF81C784))
private val overdue = ColorProvider(day = Color(0xFFC62828), night = Color(0xFFEF9A9A))

/**
 * Widget body. [openPlant] builds the tap action of one row (the exact plant), [openApp] is the tap
 * action of the rest of the widget.
 */
@Composable
fun PlantsWidgetContent(
    ui: WidgetUi,
    texts: WidgetTexts,
    openPlant: (String) -> Action,
    openApp: Action,
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(background)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(openApp),
    ) {
        Text(
            text = texts.title,
            style = TextStyle(color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(6.dp))
        when (ui) {
            WidgetUi.SignedOut -> Message(texts.signedOut)
            WidgetUi.NoPlants -> Message(texts.noPlants)
            WidgetUi.AllWatered -> Message(texts.allWatered)
            is WidgetUi.Due -> {
                ui.rows.forEach { row -> PlantRow(row, texts, openPlant(row.plantId)) }
                if (ui.moreCount > 0) {
                    Text(
                        text = texts.more(ui.moreCount),
                        style = TextStyle(color = secondaryText, fontSize = 13.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text = text, style = TextStyle(color = primaryText, fontSize = 14.sp))
}

@Composable
private fun PlantRow(row: WidgetRow, texts: WidgetTexts, open: Action) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(open),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.name,
            style = TextStyle(color = primaryText, fontSize = 14.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        val late = row.daysUntil < 0
        Text(
            text = if (late) texts.overdue(-row.daysUntil) else texts.dueToday,
            style = TextStyle(color = if (late) overdue else accent, fontSize = 13.sp),
            maxLines = 1,
        )
    }
}
