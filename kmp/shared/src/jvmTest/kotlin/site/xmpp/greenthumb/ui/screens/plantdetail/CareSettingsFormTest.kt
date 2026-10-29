package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.ui.components.stepperValue
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Care cards, variant A (M10 D§1 item 7a): typed form, stepper bounds equal to the server validator
 * (`GreenThumbPWA/server/plants/plantInput.ts`), switch off → explicit null, unchanged form → empty patch,
 * unit plurals in ru and en (VAL-CARD-008/009/010).
 */
@OptIn(ExperimentalTestApi::class)
class CareSettingsFormTest {

    private fun plant(
        water: Int = 7,
        lastWatered: String = "2026-09-01",
        fertilize: Int? = null,
        lastFertilized: String? = null,
        repot: Int? = null,
        lastRepotted: String? = null,
        prune: Int? = null,
        lastPruned: String? = null,
    ): PlantDto = PlantDto(
        id = "p1",
        userId = "24",
        name = "Ficus",
        location = "shelf",
        photoUrl = "",
        waterFrequencyDays = water,
        lastWateredDate = lastWatered,
        fertilizeFrequencyDays = fertilize,
        lastFertilizedDate = lastFertilized,
        repotFrequencyMonths = repot,
        lastRepottedDate = lastRepotted,
        pruneFrequencyMonths = prune,
        lastPrunedDate = lastPruned,
        notes = "",
        createdAt = "2026-01-01T00:00:00.000Z",
    )

    private fun wire(dto: PatchPlantDto): JsonObject =
        Json.parseToJsonElement(ApiClient.json.encodeToString(PatchPlantDto.serializer(), dto)).jsonObject

    // ---- Bounds = server validator ----------------------------------------------------------------

    @Test
    fun bounds_equal_server_validator() {
        // plantInput.ts: water_frequency_days int 1..365; fertilize_frequency_days optionalInt(3650);
        // repot/prune_frequency_months optionalInt(120). optionalInt allows 0, but 0 means "no
        // reminder" on the server (`!!days` in care/schedule.ts) and in the web client (`|| null`):
        // the app expresses it with the switch (null), so a switched-on stepper starts at 1.
        assertEquals(1..365, CareType.Water.range)
        assertEquals(1..3650, CareType.Fertilize.range)
        assertEquals(1..120, CareType.Repot.range)
        assertEquals(1..120, CareType.Prune.range)
        assertEquals(365, CareType.Water.serverMax)
        assertEquals(3650, CareType.Fertilize.serverMax)
        assertEquals(120, CareType.Repot.serverMax)
        assertEquals(120, CareType.Prune.serverMax)
    }

    @Test
    fun stepper_clamps_at_both_ends() {
        CareType.entries.forEach { type ->
            assertEquals(type.range.first, stepperValue(type.range.first, -1, type.range), "$type −")
            assertEquals(type.range.last, stepperValue(type.range.last, +1, type.range), "$type +")
            assertEquals(type.range.first + 1, stepperValue(type.range.first, +1, type.range), "$type +1")
            // A value outside the range is pulled back into it.
            assertEquals(type.range.last, stepperValue(type.range.last + 50, 0, type.range), "$type clamp")
        }
    }

    @Test
    fun form_step_clamps_each_type() {
        var form = CareSettingsForm.of(plant(water = 1, fertilize = 3650, repot = 120, prune = 1))
        form = form.step(CareType.Water, -1).step(CareType.Fertilize, +1).step(CareType.Repot, +1).step(CareType.Prune, -1)
        assertEquals(1, form.value(CareType.Water))
        assertEquals(3650, form.value(CareType.Fertilize))
        assertEquals(120, form.value(CareType.Repot))
        assertEquals(1, form.value(CareType.Prune))
        form = CareSettingsForm.of(plant(water = 365)).step(CareType.Water, +1)
        assertEquals(365, form.value(CareType.Water))
    }

    // ---- Form from the plant ------------------------------------------------------------------------

    @Test
    fun form_of_plant_maps_null_and_zero_to_off_with_defaults() {
        val form = CareSettingsForm.of(plant(fertilize = 14, repot = null, prune = 0))
        assertEquals(7, form.value(CareType.Water))
        assertTrue(form.isOn(CareType.Water), "watering is always on")
        assertTrue(form.isOn(CareType.Fertilize))
        assertEquals(14, form.value(CareType.Fertilize))
        assertTrue(!form.isOn(CareType.Repot))
        assertTrue(!form.isOn(CareType.Prune), "0 is «no reminder» on the server")
    }

    @Test
    fun switching_on_without_a_previous_value_starts_at_the_default() {
        val form = CareSettingsForm.of(plant())
        assertEquals(30, form.setOn(CareType.Fertilize, true).value(CareType.Fertilize))
        assertEquals(12, form.setOn(CareType.Repot, true).value(CareType.Repot))
        assertEquals(6, form.setOn(CareType.Prune, true).value(CareType.Prune))
        assertEquals(7, CareType.Water.default)
    }

    @Test
    fun switch_off_then_on_keeps_the_number() {
        val form = CareSettingsForm.of(plant(fertilize = 21)).setOn(CareType.Fertilize, false).setOn(CareType.Fertilize, true)
        assertEquals(21, form.value(CareType.Fertilize))
    }

    @Test
    fun water_cannot_be_switched_off() {
        val form = CareSettingsForm.of(plant()).setOn(CareType.Water, false)
        assertTrue(form.isOn(CareType.Water))
    }

    // ---- Patch --------------------------------------------------------------------------------------

    @Test
    fun unchanged_form_gives_an_empty_patch() {
        val p = plant(fertilize = 30, lastFertilized = "2026-08-01", repot = 12, prune = 0)
        val patch = CareSettingsPatch.build(p, CareSettingsForm.of(p))
        assertEquals(PatchPlantDto(), patch)
        assertTrue(patch.isEmpty())
    }

    @Test
    fun toggle_off_sends_explicit_null() {
        val p = plant(fertilize = 30, repot = 12, prune = 6)
        val form = CareSettingsForm.of(p).setOn(CareType.Repot, false)
        val patch = CareSettingsPatch.build(p, form)
        assertEquals(Patch.Null, patch.repotFrequencyMonths)
        assertEquals(Patch.Absent, patch.fertilizeFrequencyDays)
        assertEquals(Patch.Absent, patch.waterFrequencyDays)
        val body = wire(patch)
        assertIs<JsonNull>(body["repot_frequency_months"], "explicit null on the wire: $body")
    }

    @Test
    fun steps_send_the_new_values() {
        val p = plant(fertilize = 30)
        var form = CareSettingsForm.of(p)
        repeat(16) { form = form.step(CareType.Fertilize, -1) }
        form = form.step(CareType.Water, +1)
        val body = wire(CareSettingsPatch.build(p, form))
        assertEquals(14, (body["fertilize_frequency_days"] as JsonPrimitive).content.toInt())
        assertEquals(8, (body["water_frequency_days"] as JsonPrimitive).content.toInt())
        assertTrue(!body.containsKey("repot_frequency_months"), "untouched type stays out: $body")
    }

    @Test
    fun switching_on_sends_the_default() {
        val p = plant()
        val patch = CareSettingsPatch.build(p, CareSettingsForm.of(p).setOn(CareType.Prune, true))
        assertEquals(Patch.Value(6), patch.pruneFrequencyMonths)
    }

    @Test
    fun zero_on_the_server_and_off_in_the_form_is_no_change() {
        val p = plant(prune = 0)
        assertTrue(CareSettingsPatch.build(p, CareSettingsForm.of(p)).isEmpty())
    }

    @Test
    fun changed_dates_are_sent_unchanged_dates_are_not() {
        val p = plant(fertilize = 30, lastFertilized = "2026-08-01")
        val form = CareSettingsForm.of(p).copy(lastWateredDate = "2026-09-20")
        val patch = CareSettingsPatch.build(p, form)
        assertEquals(Patch.Value("2026-09-20"), patch.lastWateredDate)
        assertEquals(Patch.Absent, patch.lastFertilizedDate)
    }

    @Test
    fun the_sheet_never_touches_name_location_photo_notes() {
        val p = plant()
        val patch = CareSettingsPatch.build(p, CareSettingsForm.of(p).step(CareType.Water, +3))
        assertEquals(Patch.Absent, patch.name)
        assertEquals(Patch.Absent, patch.location)
        assertEquals(Patch.Absent, patch.photoUrl)
        assertEquals(Patch.Absent, patch.notes)
    }

    // ---- Save errors --------------------------------------------------------------------------------

    @Test
    fun save_errors_are_classified() {
        assertEquals(CareSaveError.Rejected, careSaveError(ApiError.Client(400, "{}")))
        assertEquals(CareSaveError.Server, careSaveError(ApiError.Server(503, "")))
        assertEquals(CareSaveError.Offline, careSaveError(ApiError.Network))
        assertEquals(CareSaveError.Offline, careSaveError(ApiError.Timeout))
        assertEquals(CareSaveError.Server, careSaveError(IllegalStateException("x")))
    }

    // ---- Unit plurals -------------------------------------------------------------------------------

    private var originalLocale: Locale? = null

    @AfterTest
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
        originalLocale = null
    }

    @Test
    fun unit_plurals_ru() = try {
        originalLocale = Locale.getDefault()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "ru") {
                    GreenThumbTheme(darkTheme = true) {
                        Column {
                            listOf(1, 2, 5, 21).forEach { n ->
                                Text("$n ${careUnitText(CareType.Water, n)}")
                                Text("$n ${careUnitText(CareType.Repot, n)}")
                            }
                        }
                    }
                }
            }
            listOf("1 день", "2 дня", "5 дней", "21 день", "1 месяц", "2 месяца", "5 месяцев", "21 месяц").forEach {
                onNodeWithText(it).assertExists()
            }
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun unit_plurals_en() = try {
        originalLocale = Locale.getDefault()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = true) {
                        Column {
                            listOf(1, 2, 21).forEach { n ->
                                Text("$n ${careUnitText(CareType.Fertilize, n)}")
                                Text("$n ${careUnitText(CareType.Prune, n)}")
                            }
                        }
                    }
                }
            }
            listOf("1 day", "2 days", "21 days", "1 month", "2 months", "21 months").forEach {
                onNodeWithText(it).assertExists()
            }
        }
    } finally {
        restoreLocale()
    }
}
