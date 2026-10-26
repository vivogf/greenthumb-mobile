package site.xmpp.greenthumb.ui.screens.species

import site.xmpp.greenthumb.catalog.CatalogRepository
import site.xmpp.greenthumb.catalog.SpeciesAliases
import site.xmpp.greenthumb.catalog.SpeciesEntry
import site.xmpp.greenthumb.catalog.SpeciesLight
import site.xmpp.greenthumb.catalog.SpeciesText
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.SpeciesCandidateDto
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantFields
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantForm
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** VAL-IDENT-012/013/014: score words, candidate mapping, form filling and error mapping. */
class IdentifyLogicTest {

    private val monstera = SpeciesEntry(
        id = "monstera-deliciosa",
        scientificName = "Monstera deliciosa",
        synonyms = listOf("Philodendron pertusum"),
        names = SpeciesText(ru = "Монстера деликатесная", en = "Swiss cheese plant"),
        aliases = SpeciesAliases(),
        waterIntervalDays = 8,
        fertilizeIntervalDays = 21,
        repotIntervalMonths = 24,
        light = SpeciesLight.BrightIndirect,
        tip = SpeciesText(ru = "Совет", en = "Tip"),
    )
    private val catalog = CatalogRepository(listOf(monstera))

    private fun dto(name: String, score: Double, common: List<String> = emptyList()) =
        SpeciesCandidateDto(scientificName = name, commonNames = common, score = score, gbifId = null)

    @Test
    fun score_word_boundaries() {
        assertEquals(ScoreLevel.High, scoreLevel(1.0))
        assertEquals(ScoreLevel.High, scoreLevel(0.50))
        assertEquals(ScoreLevel.Medium, scoreLevel(0.49))
        assertEquals(ScoreLevel.Medium, scoreLevel(0.20))
        assertEquals(ScoreLevel.Low, scoreLevel(0.19))
        assertEquals(ScoreLevel.Low, scoreLevel(0.0))
    }

    @Test
    fun confidence_strings_have_words_and_no_percent() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "shared/src/commonMain/composeResources").isDirectory }
        val words = mapOf(
            "values" to listOf("high", "medium", "low"),
            "values-ru" to listOf("высокая", "средняя", "низкая"),
        )
        words.forEach { (dir, expected) ->
            val xml = File(root, "shared/src/commonMain/composeResources/$dir/strings.xml").readText()
            fun value(key: String) = Regex("""<string name="$key">([^<]*)</string>""").find(xml)!!.groupValues[1]
            assertEquals(expected, listOf("High", "Medium", "Low").map { value("species_confidence$it") })
            val identifyKeys = Regex("""<string name="(species_(identify|confidence|inCatalog|poweredBy|searchCatalog)[^"]*)">([^<]*)</string>""")
                .findAll(xml).toList()
            identifyKeys.forEach { match ->
                assertFalse(match.groupValues[3].replace("%1\$s", "").contains('%'), "$dir ${match.groupValues[1]} shows %")
            }
            assertEquals("Powered by Pl@ntNet", value("species_poweredBy"))
        }
    }

    @Test
    fun candidates_keep_server_order_at_most_three_and_mark_catalog_matches() {
        val list = IdentifyLogic.toCandidates(
            listOf(
                dto("Philodendron pertusum", 0.62, listOf("", "Split-leaf")),
                dto("Unknownia rara", 0.3),
                dto("Monstera deliciosa", 0.1),
                dto("Fourth one", 0.05),
            ),
            catalog,
        )
        assertEquals(listOf("Philodendron pertusum", "Unknownia rara", "Monstera deliciosa"), list.map { it.scientificName })
        assertEquals("monstera-deliciosa", list[0].catalogEntry?.id, "synonym resolves to the catalog entry")
        assertNull(list[1].catalogEntry)
        assertEquals("Split-leaf", list[0].commonName)
        assertNull(list[1].commonName)
        assertEquals(listOf(ScoreLevel.High, ScoreLevel.Medium, ScoreLevel.Low), list.map { it.level })
    }

    @Test
    fun errors_map_to_quota_or_unavailable() {
        assertEquals(IdentifyState.QuotaExceeded, IdentifyLogic.stateForError(ApiError.Client(429, """{"code":"quota_exceeded"}""")))
        assertEquals(IdentifyState.Unavailable, IdentifyLogic.stateForError(ApiError.Server(503, """{"code":"identify_unavailable"}""")))
        assertEquals(IdentifyState.Unavailable, IdentifyLogic.stateForError(ApiError.Network))
        assertEquals(IdentifyState.Unavailable, IdentifyLogic.stateForError(ApiError.Timeout))
        assertEquals(IdentifyState.Unavailable, IdentifyLogic.stateForError(ApiError.Client(400, "{}")))
        assertEquals(IdentifyState.Unavailable, IdentifyLogic.stateForError(IllegalStateException("boom")))
    }

    @Test
    fun wire_language_follows_ui_locale() {
        assertEquals("ru", IdentifyLogic.wireLanguage("ru"))
        assertEquals("ru", IdentifyLogic.wireLanguage("ru-RU"))
        assertEquals("en", IdentifyLogic.wireLanguage("en"))
        assertEquals("en", IdentifyLogic.wireLanguage("de"))
    }

    private val catalogCandidate = IdentifyCandidate("Monstera deliciosa", "Swiss cheese plant", ScoreLevel.High, monstera)
    private val wildCandidate = IdentifyCandidate("Unknownia rara", "Rare one", ScoreLevel.Medium, null)

    @Test
    fun catalog_candidate_sets_species_and_intervals() {
        val filled = AddPlantForm.applyCandidate(AddPlantFields(), catalogCandidate, "ru")
        assertEquals("monstera-deliciosa", filled.speciesId)
        assertEquals("Monstera deliciosa", filled.speciesName)
        assertEquals("Монстера деликатесная", filled.name)
        assertEquals("8", filled.waterFrequencyText)
        assertEquals("21", filled.fertilizeFrequencyText)
        assertEquals("24", filled.repotFrequencyText)
    }

    @Test
    fun non_catalog_candidate_sets_species_name_only() {
        val manual = AddPlantFields(waterFrequencyText = "5", fertilizeFrequencyText = "12", repotFrequencyText = "18")
        val filled = AddPlantForm.applyCandidate(manual, wildCandidate, "ru")
        assertNull(filled.speciesId)
        assertEquals("Unknownia rara", filled.speciesName)
        assertEquals("Rare one", filled.name)
        assertEquals("5", filled.waterFrequencyText)
        assertEquals("12", filled.fertilizeFrequencyText)
        assertEquals("18", filled.repotFrequencyText)
        val dto = AddPlantForm.toInsertPlantDto(filled.copy(lastWateredDate = "2026-10-27"))
        assertNull(dto.speciesId)
        assertEquals("Unknownia rara", dto.speciesName)
    }

    @Test
    fun switching_from_catalog_to_non_catalog_leaves_no_stale_species_id() {
        val first = AddPlantForm.applyCandidate(AddPlantFields(), catalogCandidate, "en")
        val second = AddPlantForm.applyCandidate(first, wildCandidate, "en")
        assertNull(second.speciesId)
        assertEquals("Unknownia rara", second.speciesName)
        assertEquals("Rare one", second.name, "auto-filled name follows the new choice")
        assertEquals("8", second.waterFrequencyText, "intervals stay as they were")
        val back = AddPlantForm.applyCandidate(second, catalogCandidate, "en")
        assertEquals("monstera-deliciosa", back.speciesId)
        assertEquals("Swiss cheese plant", back.name)
    }

    @Test
    fun typed_name_survives_a_candidate() {
        val typed = AddPlantFields(name = "Моя монстера")
        assertEquals("Моя монстера", AddPlantForm.applyCandidate(typed, wildCandidate, "ru").name)
        assertEquals("Моя монстера", AddPlantForm.applyCandidate(typed, catalogCandidate, "ru").name)
    }

    @Test
    fun candidate_without_common_name_uses_scientific_name() {
        val bare = IdentifyCandidate("Unknownia rara", null, ScoreLevel.Low, null)
        assertEquals("Unknownia rara", AddPlantForm.applyCandidate(AddPlantFields(), bare, "ru").name)
    }
}
