package site.xmpp.greenthumb.ui.screens.addplant

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import site.xmpp.greenthumb.catalog.SpeciesAliases
import site.xmpp.greenthumb.catalog.SpeciesEntry
import site.xmpp.greenthumb.catalog.SpeciesLight
import site.xmpp.greenthumb.catalog.SpeciesText
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.InsertPlantDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** VAL-CATALOG-005/006/007: choosing a species in the add-plant form. */
class AddPlantSpeciesTest {

    private fun entry(id: String, ru: String, en: String, water: Int, fertilize: Int?, repot: Int?) = SpeciesEntry(
        id = id,
        scientificName = "Sci $id",
        synonyms = emptyList(),
        names = SpeciesText(ru = ru, en = en),
        aliases = SpeciesAliases(),
        waterIntervalDays = water,
        fertilizeIntervalDays = fertilize,
        repotIntervalMonths = repot,
        light = SpeciesLight.BrightIndirect,
        tip = SpeciesText(ru = "Совет", en = "Tip"),
    )

    private val a = entry("a", "Монстера", "Monstera", water = 8, fertilize = 21, repot = 24)
    private val b = entry("b", "Фикус", "Fig", water = 10, fertilize = 30, repot = 36)
    private val c = entry("c", "Литопс", "Living stones", water = 21, fertilize = null, repot = null)

    @Test
    fun empty_form_gets_name_intervals_and_species() {
        val filled = AddPlantForm.applySpecies(AddPlantFields(), a, language = "ru")
        assertEquals("Монстера", filled.name)
        assertEquals("8", filled.waterFrequencyText)
        assertEquals("21", filled.fertilizeFrequencyText)
        assertEquals("24", filled.repotFrequencyText)
        assertEquals("a", filled.speciesId)
        assertEquals("Sci a", filled.speciesName)
    }

    @Test
    fun english_ui_uses_english_name() {
        assertEquals("Monstera", AddPlantForm.applySpecies(AddPlantFields(), a, language = "en").name)
    }

    @Test
    fun switching_species_replaces_auto_name_and_all_intervals() {
        val first = AddPlantForm.applySpecies(AddPlantFields(), a, language = "ru")
        val second = AddPlantForm.applySpecies(first, b, language = "ru")
        assertEquals("Фикус", second.name)
        assertEquals("10", second.waterFrequencyText)
        assertEquals("30", second.fertilizeFrequencyText)
        assertEquals("36", second.repotFrequencyText)
        val third = AddPlantForm.applySpecies(second, c, language = "ru")
        assertEquals("Литопс", third.name)
        assertEquals("21", third.waterFrequencyText)
        assertEquals("", third.fertilizeFrequencyText)
        assertEquals("", third.repotFrequencyText)
        assertEquals("c", third.speciesId)
    }

    @Test
    fun typed_name_is_never_overwritten() {
        listOf("ru", "en").forEach { language ->
            val typed = AddPlantFields(name = "Val Fern 42", waterFrequencyText = "3", fertilizeFrequencyText = "99")
            val once = AddPlantForm.applySpecies(typed, a, language)
            assertEquals("Val Fern 42", once.name)
            assertEquals("8", once.waterFrequencyText)
            assertEquals("21", once.fertilizeFrequencyText)
            val twice = AddPlantForm.applySpecies(once, b, language)
            assertEquals("Val Fern 42", twice.name)
            assertEquals("30", twice.fertilizeFrequencyText)
        }
    }

    @Test
    fun editing_auto_name_makes_it_user_owned() {
        val filled = AddPlantForm.applySpecies(AddPlantFields(), a, language = "ru")
        val edited = filled.copy(name = "Моя монстера")
        assertEquals("Моя монстера", AddPlantForm.applySpecies(edited, b, language = "ru").name)
    }

    @Test
    fun clearing_species_keeps_name_and_intervals() {
        val filled = AddPlantForm.applySpecies(AddPlantFields(), a, language = "ru")
        val cleared = AddPlantForm.clearSpecies(filled)
        assertNull(cleared.speciesId)
        assertNull(cleared.speciesName)
        assertEquals("Монстера", cleared.name)
        assertEquals("8", cleared.waterFrequencyText)
    }

    @Test
    fun insert_body_carries_species_id_and_scientific_name() {
        val filled = AddPlantForm.applySpecies(AddPlantFields(lastWateredDate = "2026-10-27"), a, language = "ru")
        val dto = AddPlantForm.toInsertPlantDto(filled)
        assertEquals("a", dto.speciesId)
        assertEquals("Sci a", dto.speciesName)
        val wire = ApiClient.json.encodeToString(InsertPlantDto.serializer(), dto)
        val obj = ApiClient.json.parseToJsonElement(wire).jsonObject
        assertEquals("a", obj.getValue("species_id").jsonPrimitive.content)
        assertEquals("Sci a", obj.getValue("species_name").jsonPrimitive.content)
    }

    @Test
    fun insert_body_without_species_has_no_species_keys() {
        val dto = AddPlantForm.toInsertPlantDto(
            AddPlantFields(name = "X", waterFrequencyText = "7", lastWateredDate = "2026-10-27"),
        )
        val wire = ApiClient.json.encodeToString(InsertPlantDto.serializer(), dto)
        assertFalse(wire.contains("species"), wire)
    }
}
