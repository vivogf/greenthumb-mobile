package site.xmpp.greenthumb.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import site.xmpp.greenthumb.ui.res.Res

/** Path of the bundled catalog inside Compose resources (architecture §5.4). */
public const val SPECIES_CATALOG_RESOURCE: String = "files/species_catalog.json"

/** A text in both app languages. */
@Serializable
public data class SpeciesText(
    val ru: String,
    val en: String,
) {
    public fun forLanguage(language: String): String =
        if (language.lowercase().startsWith("ru")) ru else en
}

@Serializable
public data class SpeciesAliases(
    val ru: List<String> = emptyList(),
    val en: List<String> = emptyList(),
)

@Serializable
public enum class SpeciesLight {
    @SerialName("low") Low,
    @SerialName("medium") Medium,
    @SerialName("bright_indirect") BrightIndirect,
    @SerialName("direct") Direct,
}

/** One catalog entry. Intervals are single typical values, not ranges. */
@Serializable
public data class SpeciesEntry(
    val id: String,
    val scientificName: String,
    val synonyms: List<String> = emptyList(),
    val names: SpeciesText,
    val aliases: SpeciesAliases = SpeciesAliases(),
    val waterIntervalDays: Int,
    val fertilizeIntervalDays: Int? = null,
    val repotIntervalMonths: Int? = null,
    val light: SpeciesLight,
    val tip: SpeciesText,
)

/** `meta` block: who wrote the data. */
@Serializable
public data class SpeciesCatalogMeta(
    val schemaVersion: Int,
    val author: String,
    val source: String,
    val updated: String,
    val count: Int,
)

@Serializable
public data class SpeciesCatalogFile(
    val meta: SpeciesCatalogMeta,
    val species: List<SpeciesEntry>,
)

private val catalogJson = Json { ignoreUnknownKeys = true }

public fun decodeSpeciesCatalog(text: String): SpeciesCatalogFile =
    catalogJson.decodeFromString(SpeciesCatalogFile.serializer(), text)

/**
 * Process-wide catalog: read from the APK resources once, on first use.
 * No network is involved, so search works in airplane mode.
 */
public object SpeciesCatalogStore {
    private val mutex = Mutex()
    private var cached: CatalogRepository? = null

    public suspend fun repository(): CatalogRepository = mutex.withLock {
        cached ?: withContext(Dispatchers.Default) {
            CatalogRepository(decodeSpeciesCatalog(Res.readBytes(SPECIES_CATALOG_RESOURCE).decodeToString()).species)
        }.also { cached = it }
    }
}
