package site.xmpp.greenthumb.catalog

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** VAL-CATALOG-004: search on the production [CatalogRepository]. */
class CatalogRepositoryTest {

    private fun entry(
        id: String,
        scientific: String,
        ru: String,
        en: String,
        aliasesRu: List<String> = emptyList(),
        aliasesEn: List<String> = emptyList(),
        synonyms: List<String> = emptyList(),
    ) = SpeciesEntry(
        id = id,
        scientificName = scientific,
        synonyms = synonyms,
        names = SpeciesText(ru = ru, en = en),
        aliases = SpeciesAliases(ru = aliasesRu, en = aliasesEn),
        waterIntervalDays = 7,
        fertilizeIntervalDays = 14,
        repotIntervalMonths = 24,
        light = SpeciesLight.Medium,
        tip = SpeciesText(ru = "Совет", en = "Tip"),
    )

    private val fixture = CatalogRepository(
        listOf(
            entry(
                "ficus-lyrata", "Ficus lyrata", "Фикус лировидный", "Fiddle-leaf fig",
                aliasesRu = listOf("Фикус скрипковидный"), aliasesEn = listOf("Banjo fig"),
                synonyms = listOf("Ficus pandurata"),
            ),
            entry("callianthe-picta", "Callianthe picta", "Клён комнатный", "Flowering maple", aliasesRu = listOf("Абутилон")),
            entry("pelea-test", "Pellaea rotundifolia", "Пеллея", "Button fern", aliasesEn = listOf("Crème fern")),
            entry("epipremnum-aureum", "Epipremnum aureum", "Эпипремнум золотистый", "Golden pothos", aliasesRu = listOf("Сциндапсус")),
        ),
    )

    private fun ids(query: String) = fixture.search(query).map { it.id }

    @Test
    fun prefix_of_name_finds_entry() {
        assertEquals("ficus-lyrata", ids("Фикус лир").first())
        assertEquals("ficus-lyrata", ids("fiddle").first())
    }

    @Test
    fun interior_substring_finds_entry() {
        assertTrue("ficus-lyrata" in ids("ировидн"))
        assertTrue("ficus-lyrata" in ids("leaf fi"))
        assertTrue("epipremnum-aureum" in ids("премн"))
    }

    @Test
    fun case_does_not_matter() {
        val expected = ids("golden pothos")
        assertEquals(listOf("epipremnum-aureum"), expected)
        assertEquals(expected, ids("GOLDEN POTHOS"))
        assertEquals(expected, ids("gOlDeN pOtHoS"))
        assertEquals(ids("эпипремнум"), ids("ЭПИПРЕМНУМ"))
        assertEquals(ids("эпипремнум"), ids("ЭпИпРеМнУм"))
    }

    @Test
    fun yo_and_ye_are_equal() {
        assertEquals(listOf("callianthe-picta"), ids("клён"))
        assertEquals(listOf("callianthe-picta"), ids("клен"))
        assertEquals(listOf("callianthe-picta"), ids("КЛЁН"))
    }

    @Test
    fun latin_accents_composed_and_decomposed_are_equal() {
        val composed = "cr\u00e8me"
        val decomposed = "cre\u0300me"
        assertEquals(listOf("pelea-test"), ids(composed))
        assertEquals(listOf("pelea-test"), ids(decomposed))
        assertEquals(listOf("pelea-test"), ids("creme"))
        assertEquals(listOf("pelea-test"), ids("CRÈME"))
    }

    @Test
    fun aliases_in_both_languages_are_searched() {
        assertEquals(listOf("callianthe-picta"), ids("абутилон"))
        assertEquals(listOf("ficus-lyrata"), ids("banjo"))
        assertEquals(listOf("epipremnum-aureum"), ids("сциндапсус"))
    }

    @Test
    fun scientific_name_and_synonym_lookup_return_same_id() {
        assertEquals("ficus-lyrata", fixture.byScientificName("Ficus lyrata")?.id)
        assertEquals("ficus-lyrata", fixture.byScientificName("ficus pandurata")?.id)
        assertEquals("ficus-lyrata", fixture.byScientificName("Ficus lyrata Warb.")?.id)
        assertEquals(ids("Ficus lyrata"), ids("ficus pandurata"))
        assertNull(fixture.byScientificName("Ficus elastica"))
    }

    @Test
    fun nonsense_and_blank_queries_return_empty() {
        assertEquals(emptyList(), ids("zzqxw"))
        assertEquals(emptyList(), ids("   "))
    }

    @Test
    fun exact_and_prefix_matches_rank_before_contains() {
        val repo = CatalogRepository(
            listOf(
                entry("b", "Bbb bbb", "Большая пальма", "Big palm"),
                entry("a", "Aaa aaa", "Пальма", "Palm"),
            ),
        )
        assertEquals(listOf("a", "b"), repo.search("пальма").map { it.id })
    }

    @Test
    fun by_id_finds_entry() {
        assertEquals("Ficus lyrata", fixture.byId("ficus-lyrata")?.scientificName)
        assertNull(fixture.byId("missing"))
        assertNull(fixture.byId(null))
    }

    @Test
    fun real_catalog_finds_monstera_by_every_key() {
        val repo = CatalogRepository(
            decodeSpeciesCatalog(File("src/commonMain/composeResources/files/species_catalog.json").readText()).species,
        )
        val monstera = repo.byId("monstera-deliciosa")!!
        val queries = buildList {
            add(monstera.names.ru)
            add(monstera.names.en)
            add(monstera.scientificName)
            add(monstera.aliases.ru.first())
            add(monstera.aliases.en.first())
            add("МОНСТЕРА дели")
            add("cheese")
            add(monstera.synonyms.first())
        }
        queries.forEach { query ->
            assertTrue("monstera-deliciosa" in repo.search(query).map { it.id }, "query '$query'")
        }
        assertEquals("monstera-deliciosa", repo.byScientificName("Philodendron pertusum")?.id)
    }
}
