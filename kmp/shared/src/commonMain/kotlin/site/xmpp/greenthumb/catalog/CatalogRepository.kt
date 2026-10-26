package site.xmpp.greenthumb.catalog

/**
 * Offline search over the bundled catalog (architecture §5.4).
 *
 * Every name, alias, scientific name and synonym is folded with
 * [foldForSearch]: case, diacritics (ё = е, é = e, composed or decomposed)
 * and punctuation do not matter. Results are ranked: exact term, term
 * prefix, word prefix, then substring; ties keep catalog order.
 */
public class CatalogRepository(public val entries: List<SpeciesEntry>) {

    private class Indexed(val entry: SpeciesEntry, val terms: List<String>)

    private val index: List<Indexed> = entries.map { entry ->
        val raw = buildList {
            add(entry.names.ru)
            add(entry.names.en)
            addAll(entry.aliases.ru)
            addAll(entry.aliases.en)
            add(entry.scientificName)
            addAll(entry.synonyms)
        }
        Indexed(entry, raw.map(::foldForSearch).filter { it.isNotEmpty() }.distinct())
    }

    private val byIdMap: Map<String, SpeciesEntry> = entries.associateBy { it.id }

    private val byScientific: Map<String, SpeciesEntry> = buildMap {
        this@CatalogRepository.entries.forEach { entry ->
            (listOf(entry.scientificName) + entry.synonyms).forEach { name ->
                putIfAbsent(foldForSearch(name), entry)
            }
        }
    }

    public fun byId(id: String?): SpeciesEntry? = id?.let(byIdMap::get)

    /**
     * Lookup by scientific name or synonym. A trailing author citation
     * ("Ficus lyrata Warb.") is ignored by falling back to the first two words.
     */
    public fun byScientificName(name: String): SpeciesEntry? {
        val folded = foldForSearch(name)
        byScientific[folded]?.let { return it }
        val words = folded.split(' ')
        if (words.size > 2) byScientific[words.take(2).joinToString(" ")]?.let { return it }
        return null
    }

    public fun search(query: String, limit: Int = 50): List<SpeciesEntry> {
        val folded = foldForSearch(query)
        if (folded.isEmpty()) return emptyList()
        return index.withIndex()
            .mapNotNull { (position, item) ->
                val rank = item.terms.minOfOrNull { rank(it, folded) } ?: NO_MATCH
                if (rank == NO_MATCH) null else Triple(rank, position, item.entry)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .take(limit)
            .map { it.third }
    }

    /** All entries ordered by their name in [language], for browsing an empty query. */
    public fun sortedByName(language: String): List<SpeciesEntry> =
        entries.sortedBy { foldForSearch(it.names.forLanguage(language)) }

    private fun rank(term: String, query: String): Int = when {
        term == query -> 0
        term.startsWith(query) -> 1
        term.contains(" $query") -> 2
        term.contains(query) -> 3
        else -> NO_MATCH
    }

    private companion object {
        const val NO_MATCH = Int.MAX_VALUE
    }
}

/**
 * Search key: Unicode canonical decomposition, combining marks removed,
 * lower case, every non-letter/digit run turned into one space.
 */
public fun foldForSearch(text: String): String {
    val decomposed = decomposeUnicode(text)
    val builder = StringBuilder(decomposed.length)
    var pendingSpace = false
    for (char in decomposed) {
        if (char.category == CharCategory.NON_SPACING_MARK) continue
        if (char.isLetterOrDigit()) {
            if (pendingSpace && builder.isNotEmpty()) builder.append(' ')
            pendingSpace = false
            builder.append(char.lowercaseChar())
        } else {
            pendingSpace = true
        }
    }
    return builder.toString()
}

/** Unicode NFD. */
internal expect fun decomposeUnicode(text: String): String
