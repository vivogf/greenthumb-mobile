package site.xmpp.greenthumb.ui.screens.addplant

import site.xmpp.greenthumb.catalog.SpeciesEntry
import site.xmpp.greenthumb.core.network.InsertPlantDto
import site.xmpp.greenthumb.ui.screens.species.IdentifyCandidate

/**
 * Состояние формы добавления растения — порт formSchema/zod из RN
 * `app/add-plant.tsx:29-49` (фича screen-add-plant, Stage 7 п.5).
 *
 * Частоты хранятся СТРОКАМИ, как введено в поле: RN-форма тоже держит строку
 * TextInput и коерсит числом только на валидации (z.preprocess → Number).
 * Даты — `YYYY-MM-DD` или null (RN DatePickerInput value || null).
 *
 * [AddPlantForm] — чистая логика (без Compose): валидация вручную по правилам
 * `insertPlantSchema` (RN `shared/schema.ts:44-57`), БЕЗ отдельной библиотеки,
 * и сборка тела `POST /api/plants`. Проверяется напрямую в jvmTest
 * (VAL-ADDPLANT-001/003).
 */
public data class AddPlantFields(
    public val name: String = "",
    public val location: String = "",
    /** Watering interval in days as text; the stepper starts at a real 7 (M10 item 9b). */
    public val waterFrequencyText: String = AddPlantForm.DEFAULT_WATER.toString(),
    /** `YYYY-MM-DD`; RN defaultValues — todayString(). */
    public val lastWateredDate: String? = null,
    public val notes: String = "",
    public val fertilizeFrequencyText: String = "",
    public val lastFertilizedDate: String? = null,
    public val repotFrequencyText: String = "",
    public val lastRepottedDate: String? = null,
    public val pruneFrequencyText: String = "",
    public val lastPrunedDate: String? = null,
    /** data-URI фото; сам пикер — M8, до него поле остаётся "". */
    public val photoUrl: String = "",
    /** Catalog slug of the chosen species, or null. */
    public val speciesId: String? = null,
    /** Scientific name of the chosen species, or null. */
    public val speciesName: String? = null,
    /**
     * The name this form last filled in from a species. While [name] still
     * equals it, choosing another species may replace it; once the user
     * edits the name, it is theirs and is never overwritten.
     */
    public val speciesAutoName: String? = null,
    /** The watering value this form last took from a species; while unchanged it is marked «по виду». */
    public val speciesWaterText: String? = null,
)

/** Локализованные тексты ошибок (stringResource решает композиция, функция чистая). */
public data class AddPlantErrorTexts(
    public val nameRequired: String,
    public val frequencyInvalidNumber: String,
    public val frequencyMinimum: String,
    public val dateRequired: String,
)

/**
 * Ошибки валидации формы. Видимы (подсвечены) — только три поля RN
 * (`app/add-plant.tsx`: errorStyle под name / water_frequency_days /
 * last_watered_date); ошибки продвинутых частот RN НЕ показывает, но submit
 * zod-резолвером блокирует — [advancedFrequencies] переносит это поведение.
 */
public data class AddPlantErrors(
    public val name: String?,
    public val waterFrequency: String?,
    public val lastWateredDate: String?,
    public val advancedFrequencies: Boolean,
) {
    /** Submit блокируется при ЛЮБОЙ невалидной форме (RN handleSubmit). */
    public val canSubmit: Boolean
        get() = name == null && waterFrequency == null && lastWateredDate == null && !advancedFrequencies
}

public object AddPlantForm {
    /** Server bounds of `water_frequency_days` (`plantInput.ts`, same as the care settings stepper). */
    public val WATER_RANGE: IntRange = 1..365

    public const val DEFAULT_WATER: Int = 7

    /** The stepper value: the typed interval inside [WATER_RANGE], or [DEFAULT_WATER]. */
    public fun waterValue(fields: AddPlantFields): Int =
        fields.waterFrequencyText.trim().toIntOrNull()?.coerceIn(WATER_RANGE) ?: DEFAULT_WATER

    public fun withWater(fields: AddPlantFields, days: Int): AddPlantFields =
        fields.copy(waterFrequencyText = days.coerceIn(WATER_RANGE).toString())

    /** The name still is the one a species filled in. */
    public fun nameFromSpecies(fields: AddPlantFields): Boolean =
        fields.speciesAutoName != null && fields.name == fields.speciesAutoName

    /** The watering interval still is the one a species filled in. */
    public fun waterFromSpecies(fields: AddPlantFields): Boolean =
        fields.speciesWaterText != null && fields.waterFrequencyText == fields.speciesWaterText

    /** Частота «пустая» — нет введённого числа (RN: '' → undefined → поле опущено). */
    private fun parseFrequency(text: String): Int? =
        text.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

    /**
     * Валидация по правилам insertPlantSchema вручную. Частота: пустое
     * поле — «Введите число» (RN invalid_type через preprocess '' → NaN),
     * не-целое — то же (z.number().int()), ≤0 — «Минимум 1 день». Дата
     * последнего полива обязательна; продвинутые частоты — те же правила,
     * без собственной подсветки (RN-паритет).
     */
    public fun validate(fields: AddPlantFields, texts: AddPlantErrorTexts): AddPlantErrors {
        val nameError = fields.name.takeIf { it.isBlank() }?.let { texts.nameRequired }
        val waterFrequencyError = when {
            fields.waterFrequencyText.isBlank() -> texts.frequencyInvalidNumber
            fields.waterFrequencyText.toIntOrNull() == null -> texts.frequencyInvalidNumber
            fields.waterFrequencyText.toInt() < 1 -> texts.frequencyMinimum
            else -> null
        }
        val dateError = if (fields.lastWateredDate?.isNotBlank() == true) null else texts.dateRequired
        return AddPlantErrors(
            name = nameError,
            waterFrequency = waterFrequencyError,
            lastWateredDate = dateError,
            advancedFrequencies = listOf(
                fields.fertilizeFrequencyText,
                fields.repotFrequencyText,
                fields.pruneFrequencyText,
            ).any { text ->
                val parsed = text.trim().toIntOrNull()
                text.isNotBlank() && (parsed == null || parsed < 1)
            },
        )
    }

    /**
     * Тело POST — RN `onSubmit` (`app/add-plant.tsx:81-110`): trim имени/локации;
     * ШЕСТЬ независимых гейтов (`if (data.fertilize_frequency_days)`,
     * `if (data.last_fertilized_date)`, … — scrutiny m7, VAL-ADDPLANT-003):
     * каждая дата уходит без своей частоты, как repot/prune с самого начала.
     * Пустые notes/photo_url — серверный
     * дефолт `''` (encodeDefaults=false: ключи `""`-дефолтов в провод не пишутся).
     */
    public fun toInsertPlantDto(fields: AddPlantFields): InsertPlantDto {
        val fertilizer = parseFrequency(fields.fertilizeFrequencyText)
        return InsertPlantDto(
            name = fields.name.trim(),
            location = fields.location.trim(),
            photoUrl = fields.photoUrl.takeIf { it.isNotEmpty() } ?: "",
            waterFrequencyDays = fields.waterFrequencyText.trim().toInt(),
            lastWateredDate = fields.lastWateredDate.orEmpty(),
            notes = fields.notes,
            fertilizeFrequencyDays = fertilizer,
            lastFertilizedDate = parseOptionalDate(fields.lastFertilizedDate),
            repotFrequencyMonths = parseFrequency(fields.repotFrequencyText),
            lastRepottedDate = parseOptionalDate(fields.lastRepottedDate),
            pruneFrequencyMonths = parseFrequency(fields.pruneFrequencyText),
            lastPrunedDate = parseOptionalDate(fields.lastPrunedDate),
            speciesId = fields.speciesId,
            speciesName = fields.speciesName,
        )
    }

    /**
     * Choosing a catalog species (architecture §5.4): sets the species,
     * replaces the watering/fertilizing/repotting intervals with the
     * species values (a null interval empties its field) and fills the
     * name only when it is empty or was filled by a previous species.
     */
    public fun applySpecies(fields: AddPlantFields, species: SpeciesEntry, language: String): AddPlantFields {
        val nameIsAuto = fields.name.isBlank() || fields.name == fields.speciesAutoName
        val speciesName = species.names.forLanguage(language)
        return fields.copy(
            name = if (nameIsAuto) speciesName else fields.name,
            speciesAutoName = if (nameIsAuto) speciesName else null,
            waterFrequencyText = species.waterIntervalDays.toString(),
            speciesWaterText = species.waterIntervalDays.toString(),
            fertilizeFrequencyText = species.fertilizeIntervalDays?.toString() ?: "",
            repotFrequencyText = species.repotIntervalMonths?.toString() ?: "",
            speciesId = species.id,
            speciesName = species.scientificName,
        )
    }

    /**
     * Choosing an identification candidate (architecture §5.4). A catalog
     * match behaves like [applySpecies]. Any other candidate sets only
     * `species_name` (and an empty or auto-filled name); the intervals stay
     * as the user left them and no catalog id survives from an earlier choice.
     */
    public fun applyCandidate(fields: AddPlantFields, candidate: IdentifyCandidate, language: String): AddPlantFields {
        candidate.catalogEntry?.let { return applySpecies(fields, it, language) }
        val nameIsAuto = fields.name.isBlank() || fields.name == fields.speciesAutoName
        val autoName = candidate.commonName ?: candidate.scientificName
        return fields.copy(
            name = if (nameIsAuto) autoName else fields.name,
            speciesAutoName = if (nameIsAuto) autoName else null,
            speciesId = null,
            speciesName = candidate.scientificName,
            speciesWaterText = null,
        )
    }

    /** Removes the species; the name and intervals stay as they are. */
    public fun clearSpecies(fields: AddPlantFields): AddPlantFields =
        fields.copy(speciesId = null, speciesName = null, speciesAutoName = null, speciesWaterText = null)

    /** «Узнать вид по фото» is offered only for a chosen cover and while no species is set (M11 item 5). */
    public fun canIdentify(fields: AddPlantFields, hasCover: Boolean): Boolean =
        hasCover && fields.speciesId == null && fields.speciesName == null

    /** An error in a field under the collapsed «Ещё параметры» opens it, so the user sees why nothing was saved. */
    public fun opensMoreOptions(errors: AddPlantErrors): Boolean =
        errors.lastWateredDate != null || errors.advancedFrequencies

    private fun parseOptionalDate(date: String?): String? =
        date?.trim()?.takeIf { it.isNotEmpty() }
}
