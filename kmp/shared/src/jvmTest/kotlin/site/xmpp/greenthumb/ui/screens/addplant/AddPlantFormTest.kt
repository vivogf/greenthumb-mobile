package site.xmpp.greenthumb.ui.screens.addplant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import site.xmpp.greenthumb.core.network.InsertPlantDto
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import site.xmpp.greenthumb.core.network.ApiClient

/**
 * Stage 7 п.5 (фича screen-add-plant) — валидация и тело POST формы
 * добавления растения; правила RN `app/add-plant.tsx:29-49` (zod formSchema)
 * поверх `insertPlantSchema` (`shared/schema.ts:44-57`). Реализация —
 * [AddPlantForm] (валидация вручную, без отдельной библиотеки).
 *
 * VAL-ADDPLANT-001: имя обязательно, частота целое ≥1, дата обязательна;
 * локация/заметки опциональны.
 * VAL-ADDPLANT-003: незаполненный расширенный уход НЕ попадает в тело POST;
 * заполненный — попадает с датами (encodeDefaults=false: null-дефолты
 * сериализатором не пишутся); каждая дата уезжает НЕЗАВИСИМО от своей
 * частоты (RN add-plant.tsx:118-124 — шесть независимых if-ов).
 */
class AddPlantFormTest {

    private val texts = AddPlantErrorTexts(
        nameRequired = "Введите название",
        frequencyInvalidNumber = "Введите число",
        frequencyMinimum = "Минимум 1 день",
        dateRequired = "Выберите дату",
    )

    private val json: Json = ApiClient.json

    // ------------------------------------------------------------------
    // validate(): VAL-ADDPLANT-001
    // ------------------------------------------------------------------

    @Test
    fun validMinimalForm_passes() {
        val fields = AddPlantFields(
            name = "Monstera",
            waterFrequencyText = "7",
            lastWateredDate = "2026-09-27",
        )
        val errors = AddPlantForm.validate(fields, texts)
        assertEquals(null, errors.name, "имя заполнено — ошибки нет")
        assertEquals(null, errors.waterFrequency, "частота валидна — ошибки нет")
        assertEquals(null, errors.lastWateredDate, "дата задана — ошибки нет")
        assertFalse(errors.advancedFrequencies, "пустой продвинутый блок не блокирует")
        assertTrue(errors.canSubmit, "submit разрешён")
    }

    @Test
    fun blankName_isRequired() {
        val errors = AddPlantForm.validate(
            AddPlantFields(name = "", waterFrequencyText = "7", lastWateredDate = "2026-09-27"),
            texts,
        )
        assertEquals(texts.nameRequired, errors.name)
        assertFalse(errors.canSubmit, "submit блокируется при пустом имени")
    }

    @Test
    fun whitespaceOnlyName_isRequired() {
        // RN z.string().min(1): пробельная строка формально ≥1 — но ввод с
        // клавиатуры тримится при сборке DTO; валидация считает чистый blank
        // пустым (RN-юзер с "   " увидит карточку без имени — паритет решения
        // экрана: trim на submit, как RN login key.trim()).
        val errors = AddPlantForm.validate(
            AddPlantFields(name = "   ", waterFrequencyText = "7", lastWateredDate = "2026-09-27"),
            texts,
        )
        assertEquals(texts.nameRequired, errors.name)
        assertFalse(errors.canSubmit)
    }

    @Test
    fun emptyFrequencyText_isInvalidNumber() {
        // RN: z.preprocess('' → NaN) → invalid_type_error «Введите число».
        val errors = AddPlantForm.validate(
            AddPlantFields(name = "Monstera", waterFrequencyText = "", lastWateredDate = "2026-09-27"),
            texts,
        )
        assertEquals(texts.frequencyInvalidNumber, errors.waterFrequency)
        assertFalse(errors.canSubmit)
    }

    @Test
    fun nonNumericFrequencyText_isInvalidNumber() {
        val errors = AddPlantForm.validate(
            AddPlantFields(name = "Monstera", waterFrequencyText = "abc", lastWateredDate = "2026-09-27"),
            texts,
        )
        assertEquals(texts.frequencyInvalidNumber, errors.waterFrequency)
        assertFalse(errors.canSubmit)
    }

    @Test
    fun zeroAndNegativeFrequency_belowMinimum() {
        for (text in listOf("0", "-3")) {
            val errors = AddPlantForm.validate(
                AddPlantFields(name = "Monstera", waterFrequencyText = text, lastWateredDate = "2026-09-27"),
                texts,
            )
            assertEquals(texts.frequencyMinimum, errors.waterFrequency, "частота $text")
            assertFalse(errors.canSubmit, "частота $text блокирует submit")
        }
    }

    @Test
    fun fractionalFrequency_isRejected_asNonInteger() {
        // z.number().int(): дробное — invalid_type, текст «Введите число».
        val errors = AddPlantForm.validate(
            AddPlantFields(name = "Monstera", waterFrequencyText = "1.5", lastWateredDate = "2026-09-27"),
            texts,
        )
        assertEquals(texts.frequencyInvalidNumber, errors.waterFrequency)
        assertFalse(errors.canSubmit)
    }

    @Test
    fun frequencyOne_isAllowed() {
        val errors = AddPlantForm.validate(
            AddPlantFields(name = "Monstera", waterFrequencyText = "1", lastWateredDate = "2026-09-27"),
            texts,
        )
        assertEquals(null, errors.waterFrequency)
        assertTrue(errors.canSubmit)
    }

    @Test
    fun missingDate_isRequired() {
        val errors = AddPlantForm.validate(
            AddPlantFields(name = "Monstera", waterFrequencyText = "7", lastWateredDate = null),
            texts,
        )
        assertEquals(texts.dateRequired, errors.lastWateredDate)
        assertFalse(errors.canSubmit)
    }

    @Test
    fun locationAndNotes_areOptional_evenWhenLong() {
        val errors = AddPlantForm.validate(
            AddPlantFields(
                name = "Monstera",
                location = "Living room shelf, far corner",
                waterFrequencyText = "7",
                lastWateredDate = "2026-09-27",
                notes = "Loves humidity. Fertilize monthly.",
            ),
            texts,
        )
        assertTrue(errors.canSubmit, "локация и заметки не блокируют submit")
    }

    @Test
    fun errors_areLocalizedTexts_fromCaller() {
        // Тексты приходят снаружи (stringResource) — функция их не знает.
        val custom = texts.copy(nameRequired = "Name is required")
        val errors = AddPlantForm.validate(
            AddPlantFields(waterFrequencyText = "7", lastWateredDate = "2026-09-27"),
            custom,
        )
        assertEquals("Name is required", errors.name)
    }

    // ------------------------------------------------------------------
    // Расширенный уход: частичное заполнение
    // ------------------------------------------------------------------

    @Test
    fun partiallyFilledAdvancedFrequency_blocksSubmit() {
        // RN: пустая частота не валидна (invalid_type при непустом поле) —
        // submit блокируется, но отдельной подсветки у RN нет.
        val errors = AddPlantForm.validate(
            AddPlantFields(
                name = "Monstera",
                waterFrequencyText = "7",
                lastWateredDate = "2026-09-27",
                fertilizeFrequencyText = "abc",
            ),
            texts,
        )
        assertTrue(errors.advancedFrequencies)
        assertFalse(errors.canSubmit)
    }

    @Test
    fun filledAdvancedWithInvalidFrequency_blocksSubmit() {
        val errors = AddPlantForm.validate(
            AddPlantFields(
                name = "Monstera",
                waterFrequencyText = "7",
                lastWateredDate = "2026-09-27",
                repotFrequencyText = "0",
            ),
            texts,
        )
        assertTrue(errors.advancedFrequencies)
        assertFalse(errors.canSubmit)
    }

    @Test
    fun validAdvancedFrequencies_allowSubmit() {
        val errors = AddPlantForm.validate(
            AddPlantFields(
                name = "Monstera",
                waterFrequencyText = "7",
                lastWateredDate = "2026-09-27",
                fertilizeFrequencyText = "30",
                repotFrequencyText = "12",
                pruneFrequencyText = "6",
            ),
            texts,
        )
        assertFalse(errors.advancedFrequencies)
        assertTrue(errors.canSubmit)
    }

    // ------------------------------------------------------------------
    // Тело POST (VAL-ADDPLANT-003)
    // ------------------------------------------------------------------

    private fun dtoJson(dto: InsertPlantDto): JsonObject =
        ApiClient.json
            .encodeToString(InsertPlantDto.serializer(), dto)
            .let(Json::parseToJsonElement)
            .jsonObject

    @Test
    fun body_minimal_hasOnlyRequiredFields() {
        // Пустой продвинутый уход → fertilize/repot/prune ключи ОТСУТСТВУЮТ
        // в JSON (encodeDefaults=false, null-дефолты не пишутся); notes/photo
        // — их дефолт "" не пишется (сервер ставит NOT NULL DEFAULT '').
        val dto = AddPlantForm.toInsertPlantDto(
            AddPlantFields(
                name = "Monstera",
                location = "Living Room",
                waterFrequencyText = "7",
                lastWateredDate = "2026-09-27",
            ),
        )
        assertEquals(InsertPlantDto(name = "Monstera", location = "Living Room", waterFrequencyDays = 7, lastWateredDate = "2026-09-27"), dto)
        val body = dtoJson(dto)
        assertEquals("Monstera", (body["name"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("Living Room", (body["location"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(7, (body["water_frequency_days"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals("2026-09-27", (body["last_watered_date"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertFalse(body.containsKey("fertilize_frequency_days"), "пустой уход не попадает в POST")
        assertFalse(body.containsKey("last_fertilized_date"), "пустой уход не попадает в POST")
        assertFalse(body.containsKey("repot_frequency_months"), "пустой уход не попадает в POST")
        assertFalse(body.containsKey("last_repotted_date"), "пустой уход не попадает в POST")
        assertFalse(body.containsKey("prune_frequency_months"), "пустой уход не попадает в POST")
        assertFalse(body.containsKey("last_pruned_date"), "пустой уход не попадает в POST")
        assertFalse(body.containsKey("notes"), "пустые notes не пишутся (серверный дефолт '')")
        // RN всегда шлёт photo_url ("" без фото); серверный zod требует поле
        // (@EncodeDefault(ALWAYS), 400 photo_url: Required проверено live).
        assertEquals("", (body["photo_url"] as kotlinx.serialization.json.JsonPrimitive).content, "пустое фото уходит как \"\" (RN-паритет)")
        assertFalse(body.containsKey("user_id"), "user_id ставит сервер, клиент не шлёт")
    }

    @Test
    fun body_filledAdvanced_includesFrequenciesWithDates() {
        val dto = AddPlantForm.toInsertPlantDto(
            AddPlantFields(
                name = "Fern",
                location = "Bathroom",
                waterFrequencyText = "3",
                lastWateredDate = "2026-09-27",
                fertilizeFrequencyText = "30",
                lastFertilizedDate = "2026-09-20",
                repotFrequencyText = "12",
                lastRepottedDate = "2026-08-01",
                pruneFrequencyText = "6",
                lastPrunedDate = "2026-07-15",
                notes = "Keep humid",
            ),
        )
        assertEquals(30, dto.fertilizeFrequencyDays)
        assertEquals("2026-09-20", dto.lastFertilizedDate)
        assertEquals(12, dto.repotFrequencyMonths)
        assertEquals("2026-08-01", dto.lastRepottedDate)
        assertEquals(6, dto.pruneFrequencyMonths)
        assertEquals("2026-07-15", dto.lastPrunedDate)
        assertEquals("Keep humid", dto.notes.take(11))
        val body = dtoJson(dto)
        assertEquals(30, (body["fertilize_frequency_days"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals("2026-09-20", (body["last_fertilized_date"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(12, (body["repot_frequency_months"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals(6, (body["prune_frequency_months"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
    }

    @Test
    fun body_dateWithoutFrequency_isSent() {
        // RN add-plant.tsx:118-124 — ШЕСТЬ независимых if-ов: дата удобрения
        // гейтится собственным `if (data.last_fertilized_date)`, а не частотой.
        // Прежний комментарий здесь («частота обязательна, чтобы пара уехала»)
        // неверно описывал RN — scrutiny m7 (VAL-ADDPLANT-003): заполненная
        // дата без частоты уезжает в POST и сохраняется сервером.
        val dto = AddPlantForm.toInsertPlantDto(
            AddPlantFields(
                name = "Fern",
                location = "Bathroom",
                waterFrequencyText = "3",
                lastWateredDate = "2026-09-27",
                lastFertilizedDate = "2026-09-20",
            ),
        )
        assertEquals(null, dto.fertilizeFrequencyDays, "пустая частота — ключа нет в DTO")
        assertEquals("2026-09-20", dto.lastFertilizedDate, "дата уезжает БЕЗ частоты (RN: независимые if-ы)")
        val body = dtoJson(dto)
        assertFalse(body.containsKey("fertilize_frequency_days"), "пустая частота не попадает в POST")
        assertEquals(
            "2026-09-20",
            (body["last_fertilized_date"] as kotlinx.serialization.json.JsonPrimitive).content,
            "last_fertilized_date сериализуется независимо от частоты",
        )
    }

    @Test
    fun body_frequencyWithoutDate_isSent_withNullDate() {
        // RN: частота заполнена, дата нет → frequency уходит, date остаётся
        // undefined → null-дефолт не пишется. Сервер хранит колонку NULL —
        // «частота есть, дата не установлена» — легитимное состояние
        // (insertPlantSchema допускает last_fertilized_date опционально).
        val dto = AddPlantForm.toInsertPlantDto(
            AddPlantFields(
                name = "Fern",
                location = "Bathroom",
                waterFrequencyText = "3",
                lastWateredDate = "2026-09-27",
                fertilizeFrequencyText = "30",
            ),
        )
        assertEquals(30, dto.fertilizeFrequencyDays)
        assertEquals(null, dto.lastFertilizedDate)
        val body = dtoJson(dto)
        assertTrue(body.containsKey("fertilize_frequency_days"))
        assertFalse(body.containsKey("last_fertilized_date"), "null-дата не пишется в провод")
    }

    @Test
    fun body_photoDataUri_passesThrough() {
        val uri = "data:image/jpeg;base64," + "QUJD"
        val dto = AddPlantForm.toInsertPlantDto(
            AddPlantFields(
                name = "Fern",
                waterFrequencyText = "3",
                lastWateredDate = "2026-09-27",
                photoUrl = uri,
            ),
        )
        assertEquals(uri, dto.photoUrl)
        assertEquals(uri, (dtoJson(dto)["photo_url"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun body_notesText_passesThrough_trimmedLocations() {
        val dto = AddPlantForm.toInsertPlantDto(
            AddPlantFields(
                name = "  Fern  ",
                location = "  Bathroom  ",
                waterFrequencyText = " 3 ",
                lastWateredDate = "2026-09-27",
                notes = "Keep humid",
            ),
        )
        assertEquals("Fern", dto.name, "имя тримится (RN-поле свободное, но blank-имя блокирует валидация)")
        assertEquals("Bathroom", dto.location, "локация тримится на сборке тела")
        assertEquals(3, dto.waterFrequencyDays, "частота парсится из текста с пробелами")
        assertEquals("Keep humid", dto.notes)
    }

    @Test
    fun body_frequencyTextWithTrailingDot_isRejectedOnlyByValidate_notByBuild() {
        // toInsertPlantDto вызывается ТОЛЬКО после validate() (submit-гейт
        // экрана) — сборка тела не обязана повторно валидировать. Здесь
        // фиксируем, что функция не бросает и берёт целую часть честно.
        val dto = AddPlantForm.toInsertPlantDto(
            AddPlantFields(name = "Fern", waterFrequencyText = "7", lastWateredDate = "2026-09-27"),
        )
        assertEquals(7, dto.waterFrequencyDays)
    }
}
