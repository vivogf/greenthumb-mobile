package site.xmpp.greenthumb.ui.screens.plantdetail

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import site.xmpp.greenthumb.core.network.Patch

/** Items 5-6 variant A (VAL-CARD-004/005/007): pure state of the «Название и место» sheet. */
class NamePlaceFormTest {
    private val ru = PlacePresets.ru
    private val en = PlacePresets.en

    @Test
    fun russian_preset_selects_its_chip() {
        val form = NamePlaceForm.of("Фикус", "Кухня", ru)
        assertEquals(PlacePreset.Kitchen.ordinal, form.preset)
        assertFalse(form.custom)
    }

    @Test
    fun english_preset_selects_its_chip_in_a_russian_app_and_is_matched_case_insensitively() {
        val form = NamePlaceForm.of("Фикус", "  living ROOM ", ru)
        assertEquals(PlacePreset.Living.ordinal, form.preset)
        val enForm = NamePlaceForm.of("Ficus", "балкон", en)
        assertEquals(PlacePreset.Balcony.ordinal, enForm.preset)
    }

    @Test
    fun other_place_selects_custom_with_the_text_prefilled() {
        val form = NamePlaceForm.of("Фикус", "Подоконник у мамы", ru)
        assertNull(form.preset)
        assertTrue(form.custom)
        assertEquals("Подоконник у мамы", form.customText)
    }

    @Test
    fun empty_place_selects_nothing() {
        val form = NamePlaceForm.of("Фикус", "", ru)
        assertNull(form.preset)
        assertFalse(form.custom)
        assertEquals("", form.location)
    }

    @Test
    fun blank_name_is_invalid_and_gives_no_patch() {
        val form = NamePlaceForm.of("Фикус", "", ru).withName("   ")
        assertFalse(form.isValid)
        assertNull(form.toPatch())
    }

    @Test
    fun unchanged_form_gives_no_patch_even_for_a_preset_stored_in_the_other_language() {
        assertNull(NamePlaceForm.of("Ficus", "Kitchen", ru).toPatch())
        assertNull(NamePlaceForm.of("Ficus", "Подоконник", en).toPatch())
        assertNull(NamePlaceForm.of("Ficus", "", en).toPatch())
    }

    @Test
    fun name_and_place_travel_in_one_patch() {
        val patch = NamePlaceForm.of("Ficus", "", ru)
            .withName("  Big Ficus ")
            .selectPreset(PlacePreset.Kitchen.ordinal)
            .toPatch()!!
        assertEquals(Patch.Value("Big Ficus"), patch.name)
        assertEquals(Patch.Value("Кухня"), patch.location)
    }

    @Test
    fun only_changed_fields_are_sent() {
        val nameOnly = NamePlaceForm.of("Ficus", "Kitchen", en).withName("Fig").toPatch()!!
        assertEquals(Patch.Value("Fig"), nameOnly.name)
        assertEquals(Patch.Absent, nameOnly.location)
        val placeOnly = NamePlaceForm.of("Ficus", "Kitchen", en).selectPreset(PlacePreset.Office.ordinal).toPatch()!!
        assertEquals(Patch.Absent, placeOnly.name)
        assertEquals(Patch.Value("Office"), placeOnly.location)
    }

    @Test
    fun custom_text_is_saved_trimmed() {
        val patch = NamePlaceForm.of("Фикус", "", ru)
            .selectCustom()
            .withCustomText(" Подоконник у мамы ")
            .toPatch()!!
        assertEquals(Patch.Value("Подоконник у мамы"), patch.location)
    }

    @Test
    fun clearing_the_custom_text_sends_an_empty_string_not_null() {
        val patch = NamePlaceForm.of("Фикус", "Подоконник у мамы", ru).withCustomText("").toPatch()!!
        assertEquals(Patch.Value(""), patch.location)
    }

    @Test
    fun tapping_the_selected_chip_again_clears_the_place() {
        val form = NamePlaceForm.of("Фикус", "Кухня", ru).selectPreset(PlacePreset.Kitchen.ordinal)
        assertNull(form.preset)
        assertEquals(Patch.Value(""), form.toPatch()!!.location)
        val custom = NamePlaceForm.of("Фикус", "Подоконник", ru).selectCustom()
        assertFalse(custom.custom)
        assertEquals(Patch.Value(""), custom.toPatch()!!.location)
    }

    @Test
    fun choosing_a_preset_leaves_custom_and_the_other_way_round() {
        val preset = NamePlaceForm.of("Фикус", "Подоконник", ru).selectPreset(PlacePreset.Bathroom.ordinal)
        assertFalse(preset.custom)
        assertEquals("Ванная", preset.location)
        val custom = preset.selectCustom()
        assertNull(custom.preset)
        assertTrue(custom.custom)
        assertEquals("Подоконник", custom.location)
    }

    @Test
    fun inputs_stop_at_the_server_limit() {
        val long = "x".repeat(NamePlaceForm.MAX_LENGTH + 20)
        val form = NamePlaceForm.of("Ficus", "", en).withName(long).selectCustom().withCustomText(long)
        assertEquals(NamePlaceForm.MAX_LENGTH, form.name.length)
        assertEquals(NamePlaceForm.MAX_LENGTH, form.customText.length)
    }

    @Test
    fun preset_tables_match_the_string_resources() {
        val keys = listOf("placeLiving", "placeKitchen", "placeBedroom", "placeBalcony", "placeBathroom", "placeOffice")
        fun read(dir: String): List<String> {
            val xml = File("src/commonMain/composeResources/$dir/strings.xml").readText()
            return keys.map { key ->
                Regex("<string name=\"plantDetails_$key\">([^<]*)</string>").find(xml)?.groupValues?.get(1)
                    ?: error("plantDetails_$key missing in $dir")
            }
        }
        assertEquals(read("values"), en)
        assertEquals(read("values-ru"), ru)
    }
}
