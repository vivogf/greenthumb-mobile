@file:kotlinx.serialization.UseSerializers(PatchSerializer::class)

package site.xmpp.greenthumb.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.encodeToString

/**
 * VAL-NET-005: три состояния Patch-поля.
 *  - Absent не пишется в JSON вовсе (encodeDefaults = false в [ApiClient.json]);
 *  - Value(v) пишется значением;
 *  - Null пишется явным null.
 * Кодирование — тем же Json-инстансом, что установлен в ContentNegotiation,
 * чтобы тест проверял фактический провод запросов.
 */
class PatchSerializationTest {

    private val json = ApiClient.json

    @Test
    fun `all-absent patch serializes to empty object`() {
        assertEquals("{}", json.encodeToString(PatchPlantDto()))
    }

    @Test
    fun `value field is written as plain value`() {
        val body = json.encodeToString(PatchPlantDto(name = Patch.Value("Ficus")))
        assertEquals("""{"name":"Ficus"}""", body)
    }

    @Test
    fun `null field is written as explicit null`() {
        val body = json.encodeToString(PatchPlantDto(fertilizeFrequencyDays = Patch.Null))
        assertEquals("""{"fertilize_frequency_days":null}""", body)
    }

    @Test
    fun `three states coexist in one dto`() {
        // name — задать; water_frequency_days — задать; fertilize — сбросить в null;
        // остальные поля (location, notes, даты ухода) — Absent («не трогать»).
        val body = json.encodeToString(
            PatchPlantDto(
                name = Patch.Value("A"),
                waterFrequencyDays = Patch.Value(5),
                fertilizeFrequencyDays = Patch.Null,
            ),
        )
        assertEquals("""{"name":"A","water_frequency_days":5,"fertilize_frequency_days":null}""", body)
    }

    @Test
    fun `nullable value holding null is written as explicit null`() {
        // Patch<String?>: Value(null) — тоже явный null в проводе (уход = не задан).
        val body = json.encodeToString(PatchPlantDtoNullable(notes = Patch.Value(null)))
        assertEquals("""{"notes":null}""", body)
    }

    @kotlinx.serialization.Serializable
    private data class PatchPlantDtoNullable(
        @kotlinx.serialization.SerialName("notes")
        val notes: Patch<String?> = Patch.Absent,
    )

    @Test
    fun `decode maps absent null and value states`() {
        val dto = json.decodeFromString<PatchPlantDto>(
            """{"name":"A","fertilize_frequency_days":null,"repot_frequency_months":12}""",
        )
        assertEquals(Patch.Value("A"), dto.name)
        assertEquals(Patch.Null, dto.fertilizeFrequencyDays)
        assertEquals(Patch.Value(12), dto.repotFrequencyMonths)
        // Отсутствующие поля читаются как Absent (дефолт), не как Null.
        assertIs<Patch.Absent>(dto.notes)
        assertIs<Patch.Absent>(dto.location)
        assertIs<Patch.Absent>(dto.lastWateredDate)
    }

    @Test
    fun `patch roundtrip preserves three states`() {
        val wire = """{"name":"A","fertilize_frequency_days":null,"notes":null}"""
        val dto = json.decodeFromString<PatchPlantDto>(wire)
        assertEquals(wire, json.encodeToString(dto))
    }
}
