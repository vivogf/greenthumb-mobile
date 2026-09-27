package site.xmpp.greenthumb.ui.screens.plantdetail

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Stage 7 п.6 (фича screen-plant-detail) — чистая логика экрана деталей.
 *
 * VAL-DETAIL-002: месячная математика календарная (addMonths-паритет,
 * не ×30) — jvmTest на фиксированных датах.
 * VAL-DETAIL-003: сборка PATCH модалки настроек; очистка частоты уходит
 * явным null (Patch.Null) — провод проверен через ApiClient.json (тот
 * Json, что идёт на сервер).
 */
class PlantDetailLogicTest {

    // ------------------------------------------------------------------
    // daysUntilMonthCare — календарные месяцы (VAL-DETAIL-002)
    // ------------------------------------------------------------------

    @Test
    fun monthCare_jan31_plus1_clampsToFeb28_not30days() {
        // addMonths: 2026-01-31 + 1 месяц → 2026-02-28 (календарный месяц
        // клампит день), не «2026-03-02» (31+30 дней).
        val today = LocalDate.parse("2026-02-28")
        assertEquals(
            0,
            daysUntilMonthCare("2026-01-31", 1, today),
            "31 янв + 1 мес = 28 фев (клампинг дня месяца, ×30 дал бы 3 мар)",
        )
    }

    @Test
    fun monthCare_monthBoundary_endOfFeb_plus1_goesToMar31() {
        // 2026-02-28 (не високосный) + 1 мес → 2026-03-28 — обычный сдвиг,
        // без клампинга: 28 дн., а не «×30».
        val today = LocalDate.parse("2026-03-28")
        assertEquals(0, daysUntilMonthCare("2026-02-28", 1, today))
        // А 12 мес вперёд от 31 янв → 31 янв следующего года.
        val next = LocalDate.parse("2027-01-31")
        assertEquals(
            0,
            daysUntilMonthCare("2026-01-31", 12, next),
            "12 месяцев сохраняют день, когда он существует",
        )
    }

    @Test
    fun monthCare_leapYear_feb29_plus1_goesToMar29() {
        // 2024 високосный: 2024-02-29 + 1 мес → 2024-03-29.
        val today = LocalDate.parse("2024-03-29")
        assertEquals(0, daysUntilMonthCare("2024-02-29", 1, today))
    }

    @Test
    fun monthCare_leapYear_jan31_plus2_goesToMar31_notMar2() {
        // 2024-01-31 + 2 мес: фев клампится к 29, мар вернёт 31.
        val today = LocalDate.parse("2024-03-31")
        assertEquals(0, daysUntilMonthCare("2024-01-31", 2, today))
    }

    @Test
    fun monthCare_negative_isOverdueDaysCount() {
        // Полили 2026-01-01, сегодня 2026-01-15, частота 1 мес → 2026-02-01
        // → +17 дней (просрочки ещё нет). А today 2026-02-05 → -4.
        val today = LocalDate.parse("2026-02-05")
        assertEquals(-4, daysUntilMonthCare("2026-01-01", 1, today))
    }

    @Test
    fun monthCare_zeroFrequency_isNoOp() {
        val today = LocalDate.parse("2026-06-10")
        assertEquals(0, daysUntilMonthCare("2026-06-10", 0, today))
    }

    @Test
    fun monthCare_noLastDate_isNull() {
        assertEquals(null, daysUntilMonthCare(null, 6, LocalDate.parse("2026-02-28")))
        assertEquals(null, daysUntilMonthCare("", 6, LocalDate.parse("2026-02-28")))
    }

    @Test
    fun dayCare_addDays_matchesRN() {
        // Дневная частота (удобрение): 2026-09-01 + 30 дней → 2026-10-01.
        val today = LocalDate.parse("2026-10-01")
        assertEquals(0, daysUntilCare("2026-09-01", 30, today))
        // 2026-02-20 + 7 дней → 2026-02-27.
        assertEquals(0, daysUntilCare("2026-02-20", 7, LocalDate.parse("2026-02-27")))
        // Просрочка.
        assertEquals(-2, daysUntilCare("2026-02-18", 7, LocalDate.parse("2026-02-27")))
    }

    @Test
    fun dayCare_noLastDate_isNull() {
        assertEquals(null, daysUntilCare(null, 14, LocalDate.parse("2026-02-27")))
    }

    // ------------------------------------------------------------------
    // careUrgency / careDaysText / wateredAgoLabel
    // ------------------------------------------------------------------

    @Test
    fun careUrgency_categories() {
        assertEquals(CareUrgency.NotSet, careUrgency(null))
        assertEquals(CareUrgency.Overdue, careUrgency(-3))
        assertEquals(CareUrgency.Today, careUrgency(0))
        assertEquals(CareUrgency.Later, careUrgency(5))
    }

    @Test
    fun careDaysText_mapsCategories() {
        assertEquals(CareDaysText.NotSet, careDaysText(null))
        assertIs<CareDaysText.Overdue>(careDaysText(-4)).let { assertEquals(4, it.days) }
        assertEquals(CareDaysText.Today, careDaysText(0))
        assertIs<CareDaysText.DaysLeft>(careDaysText(9)).let { assertEquals(9, it.days) }
    }

    @Test
    fun wateredAgoLabel_todayYesterdayDaysAgo() {
        val today = LocalDate.parse("2026-09-25")
        assertIs<WateredAgoLabel.Today>(wateredAgoLabel("2026-09-25", today))
        assertIs<WateredAgoLabel.Yesterday>(wateredAgoLabel("2026-09-24", today))
        assertIs<WateredAgoLabel.DaysAgo>(wateredAgoLabel("2026-09-01", today)).let {
            assertEquals(24, it.days)
        }
        // Будущая дата (кривые часы) — RN isToday false, daysAgo<0 → не yesterday,
        // но и не «дн. назад»; паритет решения — трактуем как сегодня.
        assertIs<WateredAgoLabel.Today>(wateredAgoLabel("2026-09-26", today))
    }

    // ------------------------------------------------------------------
    // deleteFailureBranch — две ветки отказа удаления (VAL-DETAIL-004)
    // ------------------------------------------------------------------

    @Test
    fun deleteFailureBranch_networkAndTimeout_areQueued() {
        // Network/Timeout: журнал M4 хранит удаление и досошлёт его после
        // подключения — UI обязан показывать честный статус, не «ошибку».
        assertEquals(DeleteFailureBranch.Queued, deleteFailureBranch(ApiError.Network))
        assertEquals(DeleteFailureBranch.Queued, deleteFailureBranch(ApiError.Timeout))
    }

    @Test
    fun deleteFailureBranch_definitiveRejection_isRejected() {
        // Определённый 4xx/5xx: репозиторий откатил снимок и снял журнал —
        // UI показывает сообщение об ошибке (RN onError-паритет).
        assertEquals(DeleteFailureBranch.Rejected, deleteFailureBranch(ApiError.Server(500, "boom")))
        assertEquals(DeleteFailureBranch.Rejected, deleteFailureBranch(ApiError.Client(404, "gone")))
        assertEquals(DeleteFailureBranch.Rejected, deleteFailureBranch(ApiError.Unauthorized))
    }

    @Test
    fun deleteFailureBranch_matchesRepositoryReplayRule() {
        // Ветка UI обязана совпадать с keepForReplay репозитория: всё, что
        // остаётся в очереди, — Queued; всё, что откатывается, — Rejected.
        listOf(ApiError.Network, ApiError.Timeout).forEach { error ->
            assertEquals(DeleteFailureBranch.Queued, deleteFailureBranch(error), "$error")
        }
        listOf(
            ApiError.Server(500, "x"),
            ApiError.Client(400, "x"),
            ApiError.Unauthorized,
            IllegalStateException("boom"),
        ).forEach { error ->
            assertEquals(DeleteFailureBranch.Rejected, deleteFailureBranch(error), "$error")
        }
    }

    // ------------------------------------------------------------------
    // CareSettingsForm.of — порт RN openSettings
    // ------------------------------------------------------------------

    private fun plant(
        id: String = "p1",
        waterFrequency: Int = 7,
        lastWatered: String = "2026-09-01",
        fertilizeFrequency: Int? = null,
        lastFertilized: String? = null,
        repotFrequency: Int? = null,
        lastRepotted: String? = null,
        pruneFrequency: Int? = null,
        lastPruned: String? = null,
        notes: String = "",
    ): PlantDto = PlantDto(
        id = id,
        userId = "24",
        name = "Ficus",
        location = "shelf",
        photoUrl = "",
        waterFrequencyDays = waterFrequency,
        lastWateredDate = lastWatered,
        fertilizeFrequencyDays = fertilizeFrequency,
        lastFertilizedDate = lastFertilized,
        repotFrequencyMonths = repotFrequency,
        lastRepottedDate = lastRepotted,
        pruneFrequencyMonths = pruneFrequency,
        lastPrunedDate = lastPruned,
        notes = notes,
        createdAt = "2026-01-01T00:00:00.000Z",
    )

    @Test
    fun formOf_stringsFrequencies_nullToEmpty() {
        val form = CareSettingsForm.of(
            plant(fertilizeFrequency = 30, repotFrequency = 12, pruneFrequency = null),
        )
        assertEquals("7", form.waterFrequencyText)
        assertEquals("30", form.fertilizeFrequencyText)
        assertEquals("12", form.repotFrequencyText)
        assertEquals("", form.pruneFrequencyText, "null-частота → пустое поле (RN String(... ?: ''))")
    }

    @Test
    fun formOf_keepsDates() {
        val form = CareSettingsForm.of(
            plant(
                lastWatered = "2026-09-01",
                fertilizeFrequency = 30,
                lastFertilized = "2026-08-15",
            ),
        )
        assertEquals("2026-09-01", form.lastWateredDate)
        assertEquals("2026-08-15", form.lastFertilizedDate)
    }

    // ------------------------------------------------------------------
    // CareSettingsPatch.build — порт RN saveSettings (VAL-DETAIL-003)
    // ------------------------------------------------------------------

    @Test
    fun patch_waterFrequency_alwaysSent_fallbackToPlantValue() {
        // Поле частоты пустое → fallback на текущее значение растения
        // (RN parse(...) ?? plant.water_frequency_days).
        val patch = CareSettingsPatch.build(
            plant(waterFrequency = 7),
            CareSettingsForm.of(plant(waterFrequency = 7)).copy(waterFrequencyText = ""),
        )
        assertEquals(Patch.Value(7), patch.waterFrequencyDays)
        // Дата полива: форма инициализируется от растения — заданная дата
        // уходит (RN if (settingsForm.last_watered_date)).
        assertEquals(Patch.Value("2026-09-01"), patch.lastWateredDate)
    }

    @Test
    fun patch_waterDate_nullInForm_isAbsent() {
        // Форма без даты (RN last_watered_date ?? null) → поле не трогаем.
        val patch = CareSettingsPatch.build(
            plant(lastWatered = "2026-09-01"),
            CareSettingsForm.of(plant(lastWatered = "2026-09-01")).copy(lastWateredDate = null),
        )
        assertEquals(Patch.Absent, patch.lastWateredDate)
    }

    @Test
    fun patch_waterFrequency_textParsed() {
        val patch = CareSettingsPatch.build(
            plant(waterFrequency = 7),
            CareSettingsForm.of(plant(waterFrequency = 7)).copy(waterFrequencyText = " 5 "),
        )
        assertEquals(Patch.Value(5), patch.waterFrequencyDays)
    }

    @Test
    fun patch_datesSentOnlyWhenSet() {
        val patch = CareSettingsPatch.build(
            plant(),
            CareSettingsForm.of(plant()).copy(lastWateredDate = "2026-09-20"),
        )
        assertEquals(Patch.Value("2026-09-20"), patch.lastWateredDate)
        assertEquals(Patch.Absent, patch.lastFertilizedDate)
        assertEquals(Patch.Absent, patch.lastRepottedDate)
        assertEquals(Patch.Absent, patch.lastPrunedDate)
    }

    @Test
    fun patch_clearedFertilizeFrequency_isExplicitNull_whenPlantHadOne() {
        // У растения частота 30, в форме поле очищено → Patch.Null (сброс
        // колонки сервером) — ядро VAL-DETAIL-003.
        val patch = CareSettingsPatch.build(
            plant(fertilizeFrequency = 30, lastFertilized = "2026-08-01"),
            CareSettingsForm.of(plant(fertilizeFrequency = 30, lastFertilized = "2026-08-01"))
                .copy(fertilizeFrequencyText = ""),
        )
        assertEquals(Patch.Null, patch.fertilizeFrequencyDays)
        // Даты трогаются только если изменены: RN шлёт дату, если она
        // задана в форме — тут дата осталась в форме, уходит.
        assertEquals(Patch.Value("2026-08-01"), patch.lastFertilizedDate)
    }

    @Test
    fun patch_clearedRepotFrequency_isExplicitNull_whenPlantHadOne() {
        val patch = CareSettingsPatch.build(
            plant(repotFrequency = 12),
            CareSettingsForm.of(plant(repotFrequency = 12)).copy(repotFrequencyText = ""),
        )
        assertEquals(Patch.Null, patch.repotFrequencyMonths)
    }

    @Test
    fun patch_clearedPruneFrequency_isExplicitNull_whenPlantHadOne() {
        val patch = CareSettingsPatch.build(
            plant(pruneFrequency = 6),
            CareSettingsForm.of(plant(pruneFrequency = 6)).copy(pruneFrequencyText = ""),
        )
        assertEquals(Patch.Null, patch.pruneFrequencyMonths)
    }

    @Test
    fun patch_clearedFrequency_whenPlantHadNone_isAbsent_notNull() {
        // У растения частоты не было, поле пустое → поле не трогаем
        // (RN: else-ветка plant.fertilize_frequency_days !== null не срабатывает).
        val patch = CareSettingsPatch.build(
            plant(),
            CareSettingsForm.of(plant()).copy(fertilizeFrequencyText = ""),
        )
        assertEquals(Patch.Absent, patch.fertilizeFrequencyDays, "не было — не трогаем (не null)")
    }

    @Test
    fun patch_filledFrequencies_areValues() {
        val patch = CareSettingsPatch.build(
            plant(fertilizeFrequency = 30),
            CareSettingsForm.of(plant(fertilizeFrequency = 30))
                .copy(fertilizeFrequencyText = "14", repotFrequencyText = "12", pruneFrequencyText = "3"),
        )
        assertEquals(Patch.Value(14), patch.fertilizeFrequencyDays)
        assertEquals(Patch.Value(12), patch.repotFrequencyMonths)
        assertEquals(Patch.Value(3), patch.pruneFrequencyMonths)
    }

    @Test
    fun patch_invalidFrequencyText_clearsCare_asRN() {
        // RN-гейт ветки: `const fd = parse(...); if (fd !== undefined) Value,
        // else if (plant.fertilize_frequency_days !== null) Null`. Не-число
        // в поле при наличии у растения частоты → ЯВНЫЙ null (уход
        // отключается), а не fallback: fallback есть только у частоты полива
        // (там ?? в самом значении).
        val patch = CareSettingsPatch.build(
            plant(fertilizeFrequency = 30),
            CareSettingsForm.of(plant(fertilizeFrequency = 30)).copy(fertilizeFrequencyText = "abc"),
        )
        assertEquals(Patch.Null, patch.fertilizeFrequencyDays, "не-число → undefined → явный null (RN)")
    }

    @Test
    fun patch_zeroOrNegativeFrequencyText_clearsCare_asRN() {
        // 0/отрицательное → parse undefined → явный null при наличии у растения.
        val patch = CareSettingsPatch.build(
            plant(fertilizeFrequency = 30),
            CareSettingsForm.of(plant(fertilizeFrequency = 30)).copy(fertilizeFrequencyText = "0"),
        )
        assertEquals(Patch.Null, patch.fertilizeFrequencyDays, "0 → undefined → явный null (RN)")
    }

    @Test
    fun patch_invalidAdvancedFrequency_whenPlantHadNone_isAbsent() {
        // Не-число в поле при отсутствии частоты у растения: fd undefined,
        // plant null → поле не трогаем (RN else-ветка).
        val patch = CareSettingsPatch.build(
            plant(),
            CareSettingsForm.of(plant()).copy(fertilizeFrequencyText = "abc"),
        )
        assertEquals(Patch.Absent, patch.fertilizeFrequencyDays)
    }

    @Test
    fun patch_nameLocationPhotoNotes_absent() {
        // Модалка не правит эти поля.
        val patch = CareSettingsPatch.build(
            plant(),
            CareSettingsForm.of(plant()),
        )
        assertEquals(Patch.Absent, patch.name)
        assertEquals(Patch.Absent, patch.location)
        assertEquals(Patch.Absent, patch.photoUrl)
        assertEquals(Patch.Absent, patch.notes)
    }

    // ------------------------------------------------------------------
    // Провод PATCH через ApiClient.json (тот Json, что идёт на сервер)
    // ------------------------------------------------------------------

    private fun dtoJson(dto: PatchPlantDto): JsonObject =
        ApiClient.json
            .encodeToString(PatchPlantDto.serializer(), dto)
            .let(Json::parseToJsonElement)
            .jsonObject

    @Test
    fun wire_clearedFrequency_serializesExplicitNull() {
        val patch = CareSettingsPatch.build(
            plant(fertilizeFrequency = 30),
            CareSettingsForm.of(plant(fertilizeFrequency = 30)).copy(fertilizeFrequencyText = ""),
        )
        val body = dtoJson(patch)
        assertTrue(body.containsKey("fertilize_frequency_days"), "очищенная частота присутствует в теле")
        assertIs<kotlinx.serialization.json.JsonNull>(
            body["fertilize_frequency_days"],
            "очищенная частота уходит ЯВНЫМ null: ${body["fertilize_frequency_days"]}",
        )
    }

    @Test
    fun wire_absentFields_notInBody() {
        val patch = CareSettingsPatch.build(
            plant(), // без продвинутого ухода
            CareSettingsForm.of(plant()),
        )
        val body = dtoJson(patch)
        assertEquals(7, (body["water_frequency_days"] as JsonPrimitive).content.toInt(), "частота полива всегда уходит")
        assertTrue(body.containsKey("last_watered_date"), "дата в форме задана — уходит")
        assertTrue(!body.containsKey("fertilize_frequency_days"), "нет частоты у растения — ключа нет")
        assertTrue(!body.containsKey("name"), "имя модалкой не правится")
        assertTrue(!body.containsKey("notes"), "заметки модалкой не правятся")
    }

    @Test
    fun wire_fullSave_roundTrips() {
        val patch = CareSettingsPatch.build(
            plant(fertilizeFrequency = 30, lastFertilized = "2026-08-01"),
            CareSettingsForm.of(plant(fertilizeFrequency = 30, lastFertilized = "2026-08-01"))
                .copy(
                    waterFrequencyText = "10",
                    lastWateredDate = "2026-09-25",
                    fertilizeFrequencyText = "20",
                    lastFertilizedDate = "2026-09-01",
                ),
        )
        val body = dtoJson(patch)
        assertEquals(10, (body["water_frequency_days"] as JsonPrimitive).content.toInt())
        assertEquals("2026-09-25", (body["last_watered_date"] as JsonPrimitive).content)
        assertEquals(20, (body["fertilize_frequency_days"] as JsonPrimitive).content.toInt())
        assertEquals("2026-09-01", (body["last_fertilized_date"] as JsonPrimitive).content)
    }
}
