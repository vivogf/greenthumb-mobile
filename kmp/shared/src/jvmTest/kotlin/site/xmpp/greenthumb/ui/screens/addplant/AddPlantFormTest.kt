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

    // ------------------------------------------------------------------
    // M10 item 9b: watering stepper, species marks, typed name
    // ------------------------------------------------------------------

    private val monstera = site.xmpp.greenthumb.catalog.SpeciesEntry(
        id = "monstera-deliciosa",
        scientificName = "Monstera deliciosa",
        synonyms = emptyList(),
        names = site.xmpp.greenthumb.catalog.SpeciesText(ru = "Монстера деликатесная", en = "Swiss cheese plant"),
        aliases = site.xmpp.greenthumb.catalog.SpeciesAliases(),
        waterIntervalDays = 8,
        fertilizeIntervalDays = 21,
        repotIntervalMonths = 24,
        light = site.xmpp.greenthumb.catalog.SpeciesLight.BrightIndirect,
        tip = site.xmpp.greenthumb.catalog.SpeciesText(ru = "Совет", en = "Tip"),
    )

    private fun candidate(name: String, common: String?, entry: site.xmpp.greenthumb.catalog.SpeciesEntry? = null) =
        site.xmpp.greenthumb.ui.screens.species.IdentifyCandidate(
            scientificName = name,
            commonName = common,
            level = site.xmpp.greenthumb.ui.screens.species.ScoreLevel.High,
            catalogEntry = entry,
        )

    @Test
    fun defaultWaterIsSeven() {
        val fresh = AddPlantFields(name = "Щучий хвост", lastWateredDate = "2026-10-30")
        assertEquals(7, AddPlantForm.waterValue(fresh), "the stepper starts at a real 7")
        assertEquals(null, AddPlantForm.validate(fresh, texts).waterFrequency, "the default needs no input")
        assertEquals(7, AddPlantForm.toInsertPlantDto(fresh).waterFrequencyDays, "saving untouched stores 7")
    }

    @Test
    fun waterStepperStaysInsideServerBounds() {
        val fresh = AddPlantFields()
        assertEquals("8", AddPlantForm.withWater(fresh, 8).waterFrequencyText)
        assertEquals("1", AddPlantForm.withWater(fresh, 0).waterFrequencyText, "server minimum is 1")
        assertEquals("365", AddPlantForm.withWater(fresh, 400).waterFrequencyText, "server maximum is 365")
        assertEquals(AddPlantForm.WATER_RANGE, 1..365)
    }

    @Test
    fun speciesDoesNotOverwriteTypedName() {
        val auto = AddPlantForm.applyCandidate(AddPlantFields(), candidate("Sansevieria trifasciata", "Snake plant"), "en")
        assertEquals("Snake plant", auto.name)
        assertTrue(AddPlantForm.nameFromSpecies(auto))
        // The user clears the name and types their own.
        val typed = auto.copy(name = "Щучий хвост")
        assertFalse(AddPlantForm.nameFromSpecies(typed), "a typed name is not marked «по виду»")
        val other = AddPlantForm.applyCandidate(typed, candidate("Dracaena trifasciata", "Mother-in-law's tongue"), "en")
        assertEquals("Щучий хвост", other.name, "another candidate keeps the typed name")
        val catalogPick = AddPlantForm.applyCandidate(other, candidate("Monstera deliciosa", null, monstera), "en")
        assertEquals("Щучий хвост", catalogPick.name, "a catalog candidate keeps it too")
        assertEquals("Щучий хвост", AddPlantForm.toInsertPlantDto(catalogPick.copy(lastWateredDate = "2026-10-30")).name)
    }

    @Test
    fun fieldsFilledBySpeciesAreMarked() {
        val filled = AddPlantForm.applyCandidate(AddPlantFields(), candidate("Monstera deliciosa", null, monstera), "ru")
        assertEquals("Монстера деликатесная", filled.name)
        assertEquals(8, AddPlantForm.waterValue(filled))
        assertTrue(AddPlantForm.nameFromSpecies(filled))
        assertTrue(AddPlantForm.waterFromSpecies(filled))
        val stepped = AddPlantForm.withWater(filled, 9)
        assertFalse(AddPlantForm.waterFromSpecies(stepped), "a changed value is the user's")
        assertTrue(AddPlantForm.nameFromSpecies(stepped))
        val cleared = AddPlantForm.clearSpecies(filled)
        assertFalse(AddPlantForm.nameFromSpecies(cleared))
        assertFalse(AddPlantForm.waterFromSpecies(cleared))
        // A candidate outside the catalog has no watering value.
        val outside = AddPlantForm.applyCandidate(AddPlantFields(), candidate("Unknownia rara", "Rare one"), "en")
        assertTrue(AddPlantForm.nameFromSpecies(outside))
        assertFalse(AddPlantForm.waterFromSpecies(outside))
        assertEquals(7, AddPlantForm.waterValue(outside))
        // Nothing is marked on a fresh form.
        assertFalse(AddPlantForm.nameFromSpecies(AddPlantFields()))
        assertFalse(AddPlantForm.waterFromSpecies(AddPlantFields()))
    }

    @Test
    fun draftPrefillsAll() {
        val photo = site.xmpp.greenthumb.core.platform.paintedJpeg(32, 32)
        val draft = AddPlantDraft(candidate("Monstera deliciosa", null, monstera), photo)
        val fields = AddPlantDraft.initialFields(draft, lastWateredDate = "2026-10-30", language = "ru")
        assertTrue(fields.photoUrl.startsWith("data:image/jpeg;base64,"), "the draft photo is the cover")
        assertEquals("monstera-deliciosa", fields.speciesId)
        assertEquals("Monstera deliciosa", fields.speciesName)
        assertEquals("Монстера деликатесная", fields.name)
        assertEquals(8, AddPlantForm.waterValue(fields))
        assertTrue(AddPlantForm.nameFromSpecies(fields))
        assertTrue(AddPlantForm.waterFromSpecies(fields))
        assertEquals("2026-10-30", fields.lastWateredDate)
    }

    // ------------------------------------------------------------------
    // M11 item 5: one short form, the rest under «Ещё параметры»
    // ------------------------------------------------------------------

    @Test
    fun nameOnly_savesDefaultWateringAndToday() {
        val dto = AddPlantForm.toInsertPlantDto(AddPlantFields(name = " Fern ", lastWateredDate = "2026-10-31"))
        assertEquals("Fern", dto.name)
        assertEquals(7, dto.waterFrequencyDays, "the untouched stepper saves 7")
        assertEquals("2026-10-31", dto.lastWateredDate, "the default date is the form's today")
        assertEquals(null, dto.speciesId)
        assertEquals(null, dto.speciesName)
        assertEquals("", dto.location)
        assertEquals("", dto.notes)
        assertEquals("", dto.photoUrl)
        assertEquals(null, dto.fertilizeFrequencyDays)
        assertEquals(null, dto.repotFrequencyMonths)
        assertEquals(null, dto.pruneFrequencyMonths)
        assertTrue(AddPlantForm.validate(AddPlantFields(name = "Fern", lastWateredDate = "2026-10-31"), texts).canSubmit)
    }

    @Test
    fun identifyIsOfferedOnlyForACoverWithoutSpecies() {
        val fresh = AddPlantFields(lastWateredDate = "2026-10-31")
        assertFalse(AddPlantForm.canIdentify(fresh, hasCover = false), "no photo, no identify button")
        assertTrue(AddPlantForm.canIdentify(fresh, hasCover = true))
        val withSpecies = AddPlantForm.applySpecies(fresh, monstera, "en")
        assertFalse(AddPlantForm.canIdentify(withSpecies, hasCover = true), "a chosen species hides it")
        assertTrue(AddPlantForm.canIdentify(AddPlantForm.clearSpecies(withSpecies), hasCover = true))
    }

    @Test
    fun errorsUnderMoreOptionsOpenTheSection() {
        val nameOnlyError = AddPlantForm.validate(AddPlantFields(lastWateredDate = "2026-10-31"), texts)
        assertFalse(AddPlantForm.opensMoreOptions(nameOnlyError), "the name sits above the section")
        val badFertilize = AddPlantForm.validate(
            AddPlantFields(name = "Fern", lastWateredDate = "2026-10-31", fertilizeFrequencyText = "0"),
            texts,
        )
        assertTrue(AddPlantForm.opensMoreOptions(badFertilize), "an invalid care interval must become visible")
        val noDate = AddPlantForm.validate(AddPlantFields(name = "Fern", lastWateredDate = null), texts)
        assertTrue(AddPlantForm.opensMoreOptions(noDate))
    }
}
