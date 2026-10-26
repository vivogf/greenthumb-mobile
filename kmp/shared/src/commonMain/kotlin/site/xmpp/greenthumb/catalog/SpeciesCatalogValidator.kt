package site.xmpp.greenthumb.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public const val SPECIES_TIP_MAX_LENGTH: Int = 200

private val SLUG = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
private val INTEGER = Regex("^-?[0-9]+$")
private val LIGHT_VALUES = setOf("low", "medium", "bright_indirect", "direct")

/**
 * Checks a catalog document against the §5.4 schema. Returns one message per
 * problem; an empty list means the document is valid. Works on the raw JSON
 * so that wrong types (a string or a fraction for an interval) are reported
 * instead of being coerced by the decoder.
 */
public fun validateSpeciesCatalog(text: String): List<String> {
    val root = runCatching { Json.parseToJsonElement(text) }.getOrElse {
        return listOf("not valid JSON: ${it.message}")
    }
    if (root !is JsonObject) return listOf("top level is not an object")
    val errors = mutableListOf<String>()

    val meta = root["meta"] as? JsonObject
    if (meta == null) {
        errors += "meta is missing"
    } else {
        listOf("author", "source").forEach { key ->
            if (meta.string(key).isNullOrBlank()) errors += "meta.$key is missing or blank"
        }
    }

    val species = root["species"] as? JsonArray ?: return errors + "species is not an array"
    val ids = mutableSetOf<String>()
    val scientificNames = mutableSetOf<String>()
    species.forEachIndexed { index, element ->
        val entry = element as? JsonObject
        if (entry == null) {
            errors += "species[$index] is not an object"
            return@forEachIndexed
        }
        val label = "species[$index](${entry.string("id") ?: "?"})"
        fun fail(message: String) {
            errors += "$label: $message"
        }

        val id = entry.string("id")
        when {
            id.isNullOrBlank() -> fail("id is missing or blank")
            !SLUG.matches(id) -> fail("id '$id' is not a slug")
            !ids.add(id) -> fail("duplicate id '$id'")
        }
        val scientific = entry.string("scientificName")
        when {
            scientific.isNullOrBlank() -> fail("scientificName is missing or blank")
            !scientificNames.add(scientific.lowercase()) -> fail("duplicate scientificName '$scientific'")
        }
        if (!entry["synonyms"].isStringArray()) fail("synonyms is not a string array")

        listOf("names", "tip").forEach { key ->
            val block = entry[key] as? JsonObject
            listOf("ru", "en").forEach { language ->
                val value = block?.string(language)
                if (value.isNullOrBlank()) {
                    fail("$key.$language is missing or blank")
                } else if (key == "tip" && value.length > SPECIES_TIP_MAX_LENGTH) {
                    fail("tip.$language is ${value.length} characters (max $SPECIES_TIP_MAX_LENGTH)")
                }
            }
        }
        val aliases = entry["aliases"] as? JsonObject
        listOf("ru", "en").forEach { language ->
            if (!aliases?.get(language).isStringArray()) fail("aliases.$language is not a string array")
        }

        entry.checkInterval("waterIntervalDays", 2..60, nullable = false)?.let(::fail)
        entry.checkInterval("fertilizeIntervalDays", 7..180, nullable = true)?.let(::fail)
        entry.checkInterval("repotIntervalMonths", 6..60, nullable = true)?.let(::fail)

        val light = entry.string("light")
        if (light !in LIGHT_VALUES) fail("light '$light' is not one of $LIGHT_VALUES")
    }
    return errors
}

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.isStringArray(): Boolean =
    this is JsonArray && all { it is JsonPrimitive && it.isString }

private fun JsonObject.checkInterval(key: String, range: IntRange, nullable: Boolean): String? {
    val value = get(key)
    if (value == null || value is JsonNull) {
        return if (nullable) null else "$key is missing"
    }
    if (value !is JsonPrimitive || value.isString || !INTEGER.matches(value.content)) {
        return "$key is not an integer"
    }
    val number = value.content.toLongOrNull()
    return if (number == null || number !in range.first..range.last) "$key=${value.content} is outside $range" else null
}
