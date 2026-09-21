package site.xmpp.greenthumb.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString

/**
 * VAL-NET-006: `id`/`user_id` на проводе и числом, и строкой разбираются
 * одинаково в одну модель. RN-источник: `app/_layout.tsx:52` (`plant_id: number | string`)
 * и `app/plant/[id].tsx:57` (`p.id === id` для id строкой в params).
 * Сериализатор — [StringOrNumberAsStringSerializer].
 */
class StringOrNumberAsIdTest {

    private val json = ApiClient.json

    @Test
    fun `plant id as number and as string decode to the same model`() {
        val asNumber =
            """{"id":1,"user_id":"u","name":"F","location":"L","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"","created_at":"2026-09-20T10:00:00.000Z"}"""
        val asString =
            """{"id":"1","user_id":"u","name":"F","location":"L","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"","created_at":"2026-09-20T10:00:00.000Z"}"""

        val fromNumber = json.decodeFromString<PlantDto>(asNumber)
        val fromString = json.decodeFromString<PlantDto>(asString)
        assertEquals(fromString, fromNumber)
        assertEquals("1", fromNumber.id)
    }

    @Test
    fun `uuid string id and numeric-shaped id both keep exact text`() {
        val uuidPlant =
            """{"id":"550e8400-e29b-41d4-a716-446655440000","user_id":"2","name":"F","location":"L","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"","created_at":"2026-09-20T10:00:00.000Z"}"""
        val dto = json.decodeFromString<PlantDto>(uuidPlant)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", dto.id)
        assertEquals("2", dto.userId)
    }

    @Test
    fun `numeric user_id in response decodes as string`() {
        // Раньше сервер писал user_id числом; парсинг не должен падать.
        val dto =
            json.decodeFromString<PlantDto>(
                """{"id":"550e8400-e29b-41d4-a716-446655440000","user_id":7,"name":"F","location":"L","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"","created_at":"2026-09-20T10:00:00.000Z"}""",
            )
        assertEquals("7", dto.userId)
    }

    @Test
    fun `serializing numeric-shaped ids keeps text form`() {
        val dto = PlantDto(
            id = "550e8400-e29b-41d4-a716-446655440000",
            userId = "2",
            name = "F",
            location = "L",
            photoUrl = "",
            waterFrequencyDays = 7,
            lastWateredDate = "2026-09-20",
            fertilizeFrequencyDays = null,
            lastFertilizedDate = null,
            repotFrequencyMonths = null,
            lastRepottedDate = null,
            pruneFrequencyMonths = null,
            lastPrunedDate = null,
            notes = "",
            createdAt = "2026-09-20T10:00:00.000Z",
        )
        val wire = json.encodeToString(dto)
        // Дефолты (notes="" и nullable-null) в провод не пишутся (encodeDefaults=false).
        assertEquals(
            """{"id":"550e8400-e29b-41d4-a716-446655440000","user_id":"2","name":"F","location":"L","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20","created_at":"2026-09-20T10:00:00.000Z"}""",
            wire,
        )
    }

    @Test
    fun `plants list with mixed id shapes parses as one list`() {
        val list = """
            [{"id":3,"user_id":5,"name":"A","location":"L","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"","created_at":"2026-09-20T10:00:00.000Z"},
             {"id":"550e8400-e29b-41d4-a716-446655440000","user_id":"5","name":"B","location":"L","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"","created_at":"2026-09-20T10:00:00.000Z"}]
        """.trimIndent()
        val plants = json.decodeFromString<List<PlantDto>>(list)
        assertEquals(2, plants.size)
        assertEquals("3", plants[0].id)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", plants[1].id)
    }
}
