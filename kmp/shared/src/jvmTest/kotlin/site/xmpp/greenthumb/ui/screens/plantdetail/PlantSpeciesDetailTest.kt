package site.xmpp.greenthumb.ui.screens.plantdetail

import site.xmpp.greenthumb.catalog.CatalogRepository
import site.xmpp.greenthumb.catalog.SpeciesAliases
import site.xmpp.greenthumb.catalog.SpeciesEntry
import site.xmpp.greenthumb.catalog.SpeciesLight
import site.xmpp.greenthumb.catalog.SpeciesText
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto
import site.xmpp.greenthumb.core.network.PlantDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** VAL-CATALOG-008/009/010/011: species on the detail screen and in edit. */
class PlantSpeciesDetailTest {

    private fun entry(id: String) = SpeciesEntry(
        id = id,
        scientificName = "Sci $id",
        synonyms = emptyList(),
        names = SpeciesText(ru = "Рус $id", en = "Eng $id"),
        aliases = SpeciesAliases(),
        waterIntervalDays = 7,
        fertilizeIntervalDays = null,
        repotIntervalMonths = null,
        light = SpeciesLight.Low,
        tip = SpeciesText(ru = "Совет $id", en = "Tip $id"),
    )

    private val catalog = CatalogRepository(listOf(entry("a"), entry("b")))

    private fun plant(speciesId: String? = null, speciesName: String? = null) = PlantDto(
        id = "p1",
        userId = "u1",
        name = "Plant",
        location = "",
        photoUrl = "",
        waterFrequencyDays = 7,
        lastWateredDate = "2026-10-20",
        createdAt = "2026-10-01T00:00:00.000Z",
        speciesId = speciesId,
        speciesName = speciesName,
    )

    @Test
    fun catalog_species_resolves_to_full_info() {
        val info = plantSpeciesInfo(plant("a", "Sci a"), catalog)
        assertIs<PlantSpeciesInfo.Catalog>(info)
        assertEquals("a", info.entry.id)
    }

    @Test
    fun plant_without_species_has_no_info() {
        assertEquals(PlantSpeciesInfo.None, plantSpeciesInfo(plant(), catalog))
        assertEquals(PlantSpeciesInfo.None, plantSpeciesInfo(plant(), null))
    }

    @Test
    fun unknown_id_or_name_only_shows_name() {
        assertEquals(PlantSpeciesInfo.NameOnly("Sci x"), plantSpeciesInfo(plant("x", "Sci x"), catalog))
        assertEquals(PlantSpeciesInfo.NameOnly("Rosa canina"), plantSpeciesInfo(plant(null, "Rosa canina"), catalog))
        // Catalog not loaded yet: still show the saved name rather than nothing.
        assertEquals(PlantSpeciesInfo.NameOnly("Sci a"), plantSpeciesInfo(plant("a", "Sci a"), null))
    }

    @Test
    fun unchanged_species_is_absent_from_patch() {
        val p = plant("a", "Sci a")
        val patch = CareSettingsPatch.build(p, CareSettingsForm.of(p))
        assertEquals(Patch.Absent, patch.speciesId)
        assertEquals(Patch.Absent, patch.speciesName)
    }

    @Test
    fun changed_species_sends_both_fields() {
        val p = plant("a", "Sci a")
        val form = CareSettingsForm.of(p).withSpecies(entry("b"))
        val patch = CareSettingsPatch.build(p, form)
        assertEquals(Patch.Value("b"), patch.speciesId)
        assertEquals(Patch.Value("Sci b"), patch.speciesName)
    }

    @Test
    fun cleared_species_sends_explicit_nulls() {
        val p = plant("a", "Sci a")
        val form = CareSettingsForm.of(p).withoutSpecies()
        val patch = CareSettingsPatch.build(p, form)
        assertEquals(Patch.Null, patch.speciesId)
        assertEquals(Patch.Null, patch.speciesName)
        val wire = ApiClient.json.encodeToString(PatchPlantDto.serializer(), patch)
        assertTrue(wire.contains("\"species_id\":null"), wire)
        assertTrue(wire.contains("\"species_name\":null"), wire)
    }

    @Test
    fun plant_without_species_edit_sends_no_species_fields() {
        val p = plant()
        val wire = ApiClient.json.encodeToString(
            PatchPlantDto.serializer(),
            CareSettingsPatch.build(p, CareSettingsForm.of(p).copy(waterFrequencyText = "5")),
        )
        assertFalse(wire.contains("species"), wire)
    }
}
