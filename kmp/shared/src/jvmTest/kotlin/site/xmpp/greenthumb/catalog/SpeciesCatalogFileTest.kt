package site.xmpp.greenthumb.catalog

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import site.xmpp.greenthumb.ui.res.Res
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * VAL-CATALOG-001/002: the bundled catalog file itself (not a copy) passes the
 * production validator, and the validator rejects every broken fixture.
 */
class SpeciesCatalogFileTest {

    private val sourceFile = File("src/commonMain/composeResources/files/species_catalog.json")
    private val text: String = sourceFile.readText()

    @Test
    fun bundled_resource_is_the_source_file() = runBlocking {
        val bundled = Res.readBytes(SPECIES_CATALOG_RESOURCE)
        assertTrue(bundled.contentEquals(sourceFile.readBytes()), "Res bytes differ from the source file")
    }

    @Test
    fun real_catalog_passes_validation() {
        val errors = validateSpeciesCatalog(text)
        assertEquals(emptyList(), errors, "real catalog errors")
    }

    @Test
    fun real_catalog_has_200_to_300_entries_and_monstera() {
        val catalog = decodeSpeciesCatalog(text)
        println("species_catalog.json entries checked: ${catalog.species.size}")
        assertTrue(catalog.species.size in 200..300, "count ${catalog.species.size}")
        val monstera = catalog.species.single { it.scientificName == "Monstera deliciosa" }
        assertEquals("monstera-deliciosa", monstera.id)
    }

    @Test
    fun meta_states_project_authorship_and_no_third_party_terms() {
        val meta = decodeSpeciesCatalog(text).meta
        assertEquals("GreenThumb project", meta.author)
        assertTrue(meta.source.contains("GreenThumb"), meta.source)
        assertEquals(decodeSpeciesCatalog(text).species.size, meta.count)
        listOf("license", "licence", "cc-by", "copyright", "wikipedia", "gbif").forEach { word ->
            assertFalse(text.contains(word, ignoreCase = true), "catalog mentions '$word'")
        }
    }

    // ---------------------------------------------------------------
    // Negative fixtures: each one must be rejected.
    // ---------------------------------------------------------------

    private val validEntry: JsonObject = Json.parseToJsonElement(
        """
        {"id":"test-plant","scientificName":"Testus plantus","synonyms":["Oldus plantus"],
         "names":{"ru":"Тестовое","en":"Test plant"},"aliases":{"ru":["Тест"],"en":["Tester"]},
         "waterIntervalDays":7,"fertilizeIntervalDays":14,"repotIntervalMonths":24,
         "light":"medium","tip":{"ru":"Поливайте умеренно.","en":"Water moderately."}}
        """.trimIndent(),
    ).jsonObject

    private fun doc(vararg entries: JsonObject): String = JsonObject(
        mapOf(
            "meta" to JsonObject(
                mapOf(
                    "schemaVersion" to JsonPrimitive(1),
                    "author" to JsonPrimitive("GreenThumb project"),
                    "source" to JsonPrimitive("Authored by the GreenThumb project."),
                    "updated" to JsonPrimitive("2026-10-27"),
                    "count" to JsonPrimitive(entries.size),
                ),
            ),
            "species" to JsonArray(entries.toList()),
        ),
    ).toString()

    private fun JsonObject.with(key: String, value: JsonElement): JsonObject =
        JsonObject(toMutableMap().apply { put(key, value) })

    private fun JsonObject.without(key: String): JsonObject =
        JsonObject(toMutableMap().apply { remove(key) })

    private fun JsonObject.withNested(key: String, inner: String, value: JsonElement?): JsonObject {
        val nested = getValue(key).jsonObject.toMutableMap()
        if (value == null) nested.remove(inner) else nested[inner] = value
        return with(key, JsonObject(nested))
    }

    private fun assertRejected(name: String, json: String) {
        val errors = validateSpeciesCatalog(json)
        println("negative '$name' -> $errors")
        assertTrue(errors.isNotEmpty(), "fixture '$name' was accepted")
    }

    @Test
    fun valid_fixture_is_accepted() {
        assertEquals(emptyList(), validateSpeciesCatalog(doc(validEntry)))
    }

    @Test
    fun null_optional_intervals_are_accepted() {
        val entry = validEntry.with("fertilizeIntervalDays", JsonNull).with("repotIntervalMonths", JsonNull)
        assertEquals(emptyList(), validateSpeciesCatalog(doc(entry)))
    }

    @Test
    fun duplicate_id_is_rejected() {
        val other = validEntry.with("scientificName", JsonPrimitive("Otherus plantus"))
        assertRejected("duplicate id", doc(validEntry, other))
    }

    @Test
    fun duplicate_scientific_name_is_rejected() {
        val other = validEntry.with("id", JsonPrimitive("other-plant"))
        assertRejected("duplicate scientificName", doc(validEntry, other))
    }

    @Test
    fun blank_or_bad_slug_id_is_rejected() {
        assertRejected("blank id", doc(validEntry.with("id", JsonPrimitive(" "))))
        assertRejected("non-slug id", doc(validEntry.with("id", JsonPrimitive("Test Plant"))))
    }

    @Test
    fun missing_names_ru_is_rejected() {
        assertRejected("missing names.ru", doc(validEntry.withNested("names", "ru", null)))
        assertRejected("blank names.ru", doc(validEntry.withNested("names", "ru", JsonPrimitive(""))))
    }

    @Test
    fun missing_tip_en_is_rejected() {
        assertRejected("missing tip.en", doc(validEntry.withNested("tip", "en", null)))
    }

    @Test
    fun tip_longer_than_200_is_rejected() {
        assertRejected("201-char tip", doc(validEntry.withNested("tip", "ru", JsonPrimitive("а".repeat(201)))))
        assertEquals(emptyList(), validateSpeciesCatalog(doc(validEntry.withNested("tip", "ru", JsonPrimitive("а".repeat(200))))))
    }

    @Test
    fun unknown_light_is_rejected() {
        assertRejected("light sunny", doc(validEntry.with("light", JsonPrimitive("sunny"))))
    }

    @Test
    fun water_interval_bounds() {
        assertRejected("water 1", doc(validEntry.with("waterIntervalDays", JsonPrimitive(1))))
        assertRejected("water 61", doc(validEntry.with("waterIntervalDays", JsonPrimitive(61))))
        assertRejected("water null", doc(validEntry.with("waterIntervalDays", JsonNull)))
        assertEquals(emptyList(), validateSpeciesCatalog(doc(validEntry.with("waterIntervalDays", JsonPrimitive(2)))))
        assertEquals(emptyList(), validateSpeciesCatalog(doc(validEntry.with("waterIntervalDays", JsonPrimitive(60)))))
    }

    @Test
    fun fertilize_interval_bounds() {
        assertRejected("fertilize 6", doc(validEntry.with("fertilizeIntervalDays", JsonPrimitive(6))))
        assertRejected("fertilize 181", doc(validEntry.with("fertilizeIntervalDays", JsonPrimitive(181))))
    }

    @Test
    fun repot_interval_bounds() {
        assertRejected("repot 5", doc(validEntry.with("repotIntervalMonths", JsonPrimitive(5))))
        assertRejected("repot 61", doc(validEntry.with("repotIntervalMonths", JsonPrimitive(61))))
    }

    @Test
    fun non_integer_interval_is_rejected() {
        assertRejected("water 7.5", doc(validEntry.with("waterIntervalDays", JsonPrimitive(7.5))))
        assertRejected("water \"7\"", doc(validEntry.with("waterIntervalDays", JsonPrimitive("7"))))
        assertRejected("range object", doc(validEntry.with("waterIntervalDays", Json.parseToJsonElement("""{"min":5,"max":9}"""))))
    }

    @Test
    fun synonyms_and_aliases_must_be_string_arrays() {
        assertRejected("synonyms string", doc(validEntry.with("synonyms", JsonPrimitive("Oldus"))))
        assertRejected("aliases.en number", doc(validEntry.withNested("aliases", "en", JsonArray(listOf(JsonPrimitive(3))))))
        assertRejected("aliases.ru missing", doc(validEntry.withNested("aliases", "ru", null)))
    }

    @Test
    fun missing_meta_or_species_is_rejected() {
        assertRejected("no meta", JsonObject(mapOf("species" to JsonArray(listOf(validEntry)))).toString())
        assertRejected("no species", JsonObject(mapOf("meta" to Json.parseToJsonElement(doc()).jsonObject.getValue("meta"))).toString())
        assertRejected("not json", "{")
    }

    @Test
    fun real_catalog_entries_round_trip_through_the_decoder() {
        val raw = Json.parseToJsonElement(text).jsonObject.getValue("species").jsonArray.size
        assertEquals(raw, decodeSpeciesCatalog(text).species.size)
    }
}
