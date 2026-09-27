package site.xmpp.greenthumb.ui.screens.addplant

import site.xmpp.greenthumb.core.network.InsertPlantDto

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
    /** Частота полива — текст поля ввода (RN keyboardType numeric). */
    public val waterFrequencyText: String = "",
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
        )
    }

    private fun parseOptionalDate(date: String?): String? =
        date?.trim()?.takeIf { it.isNotEmpty() }
}
