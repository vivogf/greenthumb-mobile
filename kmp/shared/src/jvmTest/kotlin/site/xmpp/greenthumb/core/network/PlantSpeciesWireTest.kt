package site.xmpp.greenthumb.core.network

import site.xmpp.greenthumb.core.storage.PlantEntity
import site.xmpp.greenthumb.data.applying
import site.xmpp.greenthumb.data.toDto
import site.xmpp.greenthumb.data.toEntity
import site.xmpp.greenthumb.data.toOptimisticEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** VAL-CATALOG-007/011: species fields on the wire and in the local copy. */
class PlantSpeciesWireTest {

    private val json = ApiClient.json

    @Test
    fun pre_m3_plant_json_without_species_fields_decodes_as_no_species() {
        val legacy = """
            {"id":"7","user_id":"3","name":"Old","location":"","photo_url":"","water_frequency_days":7,
             "last_watered_date":"2026-01-01","notes":"","created_at":"2026-01-01T00:00:00.000Z"}
        """.trimIndent()
        val plant = json.decodeFromString(PlantDto.serializer(), legacy)
        assertNull(plant.speciesId)
        assertNull(plant.speciesName)
        val entity: PlantEntity = plant.toEntity()
        assertNull(entity.speciesId)
        assertNull(entity.toDto().speciesName)
    }

    @Test
    fun server_species_fields_decode_and_survive_room_mapping() {
        val body = """
            {"id":"7","user_id":"3","name":"M","location":"","photo_url":"","water_frequency_days":8,
             "last_watered_date":"2026-10-01","created_at":"2026-10-01T00:00:00.000Z",
             "species_id":"monstera-deliciosa","species_name":"Monstera deliciosa"}
        """.trimIndent()
        val plant = json.decodeFromString(PlantDto.serializer(), body)
        assertEquals("monstera-deliciosa", plant.speciesId)
        assertEquals(plant, plant.toEntity().toDto().copy(notes = plant.notes))
    }

    @Test
    fun explicit_null_species_from_server_decodes() {
        val body = """
            {"id":"7","user_id":"3","name":"M","location":"","photo_url":"","water_frequency_days":8,
             "last_watered_date":"2026-10-01","created_at":"2026-10-01T00:00:00.000Z",
             "species_id":null,"species_name":null}
        """.trimIndent()
        assertNull(json.decodeFromString(PlantDto.serializer(), body).speciesId)
    }

    @Test
    fun local_patch_sets_and_clears_species() {
        val base = PlantDto(
            id = "p", userId = "u", name = "n", location = "", photoUrl = "", waterFrequencyDays = 7,
            lastWateredDate = "2026-10-01", createdAt = "x",
        ).toEntity()
        val set = base.applying(PatchPlantDto(speciesId = Patch.Value("a"), speciesName = Patch.Value("Sci a")))
        assertEquals("a", set.speciesId)
        assertEquals("Sci a", set.speciesName)
        val cleared = set.applying(PatchPlantDto(speciesId = Patch.Null, speciesName = Patch.Null))
        assertNull(cleared.speciesId)
        assertNull(cleared.speciesName)
        assertEquals("a", set.applying(PatchPlantDto(name = Patch.Value("z"))).speciesId)
    }

    @Test
    fun optimistic_insert_keeps_species() {
        val dto = InsertPlantDto(
            name = "n", location = "", waterFrequencyDays = 7, lastWateredDate = "2026-10-01",
            speciesId = "a", speciesName = "Sci a",
        )
        val entity = dto.toOptimisticEntity("local", "u", "now")
        assertEquals("a", entity.speciesId)
        assertEquals("Sci a", entity.speciesName)
    }
}
