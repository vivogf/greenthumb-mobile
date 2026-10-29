package site.xmpp.greenthumb.ui.screens.plantdetail

import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto

/** Ready places of the «Название и место» sheet, in chip order. */
public enum class PlacePreset { Living, Kitchen, Bedroom, Balcony, Bathroom, Office }

/**
 * Preset labels per app language, in [PlacePreset] order. They mirror `plantDetails_place*` in both
 * `strings.xml` (pinned by `NamePlaceFormTest`): the server stores the place as free text, so a place
 * saved in one language must still select its chip after the user switches the app language.
 */
public object PlacePresets {
    public val ru: List<String> = listOf("Гостиная", "Кухня", "Спальня", "Балкон", "Ванная", "Офис")
    public val en: List<String> = listOf("Living room", "Kitchen", "Bedroom", "Balcony", "Bathroom", "Office")

    internal fun labelsFor(language: String): List<String> = if (language.startsWith("ru")) ru else en

    /** Index of the preset whose ru or en label equals [location] (trimmed, case-insensitive). */
    internal fun match(location: String): Int? {
        val value = location.trim()
        if (value.isEmpty()) return null
        return PlacePreset.entries.indexOfFirst { preset ->
            ru[preset.ordinal].equals(value, ignoreCase = true) || en[preset.ordinal].equals(value, ignoreCase = true)
        }.takeIf { it >= 0 }
    }
}

/**
 * State of the «Название и место» sheet (items 5-6, variant A). Nothing is saved until [toPatch] is sent:
 * cancel paths simply drop the form.
 *
 * [labels] are the preset labels of the app language; a newly chosen preset is stored with that label.
 * A preset that was already selected when the sheet opened keeps the stored text, so an untouched
 * «Kitchen» in a Russian app is not rewritten to «Кухня».
 */
public data class NamePlaceForm(
    public val originalName: String,
    public val originalLocation: String,
    public val name: String,
    public val preset: Int?,
    public val custom: Boolean,
    public val customText: String,
    public val labels: List<String>,
    private val initialPreset: Int?,
) {
    /** The place the form would save; `""` means no place. */
    public val location: String
        get() = when {
            preset != null && preset == initialPreset -> originalLocation
            preset != null -> labels[preset]
            custom -> customText.trim()
            else -> ""
        }

    public val isValid: Boolean get() = name.trim().isNotEmpty()

    public fun withName(value: String): NamePlaceForm = copy(name = value.take(MAX_LENGTH))

    /** Tapping the selected chip again clears the place. */
    public fun selectPreset(index: Int): NamePlaceForm =
        if (preset == index) copy(preset = null) else copy(preset = index, custom = false)

    /** «Своё…»: shows the free-text field; tapping it again while selected clears the place. */
    public fun selectCustom(): NamePlaceForm =
        if (custom) copy(custom = false) else copy(custom = true, preset = null)

    public fun withCustomText(value: String): NamePlaceForm = copy(customText = value.take(MAX_LENGTH))

    /** One PATCH with the changed fields; null when the name is blank or nothing changed. */
    public fun toPatch(): PatchPlantDto? {
        if (!isValid) return null
        val newName = name.trim()
        val newLocation = location
        val nameChanged = newName != originalName
        val locationChanged = newLocation != originalLocation
        if (!nameChanged && !locationChanged) return null
        return PatchPlantDto(
            name = if (nameChanged) Patch.Value(newName) else Patch.Absent,
            location = if (locationChanged) Patch.Value(newLocation) else Patch.Absent,
        )
    }

    public companion object {
        /** Server limit for `name` and `location` (`server/plants/plantInput.ts`). */
        public const val MAX_LENGTH: Int = 200

        public fun of(name: String, location: String, labels: List<String>): NamePlaceForm {
            val matched = PlacePresets.match(location)
            val custom = matched == null && location.isNotBlank()
            return NamePlaceForm(
                originalName = name,
                originalLocation = location,
                name = name,
                preset = matched,
                custom = custom,
                customText = if (custom) location.trim() else "",
                labels = labels,
                initialPreset = matched,
            )
        }
    }
}
