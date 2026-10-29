package site.xmpp.greenthumb.core.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.serialization.StringFormat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import site.xmpp.greenthumb.core.platform.PushSubscriptions

/**
 * Типизированный клиент API GreenThumb — один suspend-метод на эндпоинт
 * таблицы контракта (`library/backend-contract.md`; план Stage 2 п.6).
 *
 * Бросает [ApiError] на не-OK статус (уровень `request` в [ApiClient]);
 * тела кодируются/декодируются тем же [ApiClient.json] (encodeDefaults=false —
 * критично для Patch.Absent), разбор 200-тела — ContentNegotiation-эквивалент.
 * Даты в телах — `YYYY-MM-DD` (никогда ISO со временем; K3-правило гейта).
 *
 * 15 эндпоинтов Stage 2 (таблица плана) + `update-timezone` (таблица контракта
 * №6, порт `AuthContext.syncTimezoneInBackground`) + FCM-троица и
 * `DELETE /api/auth/account` (mission-scope M9/M12; backend-contract.md).
 * Всего 20 методов — полная таблица контракта без web-only
 * `/api/push/subscribe|test|vapid` (web-push — PWA-клиент).
 */
class GreenThumbApi(private val client: ApiClient) {

    private val json: StringFormat = ApiClient.json

    // ------------------------------------------------------------------
    // Auth (8)
    // ------------------------------------------------------------------

    /** GET /api/auth/me → { user } */
    suspend fun me(): UserDto =
        request<UserEnvelope>(HttpMethod.Get, "/api/auth/me").user

    /** POST /api/auth/create-anonymous { name? } → { user } */
    suspend fun createAnonymous(name: String? = null): UserDto =
        request<UserEnvelope, NameBody>(HttpMethod.Post, "/api/auth/create-anonymous", NameBody(name)).user

    /** POST /api/auth/login-recovery { recoveryKey } → { user } */
    suspend fun loginRecovery(recoveryKey: String): UserDto =
        request<UserEnvelope, RecoveryKeyRequest>(
            HttpMethod.Post,
            "/api/auth/login-recovery",
            RecoveryKeyRequest(recoveryKey),
        ).user

    /** POST /api/auth/logout → { success } */
    suspend fun logout(): SuccessResult =
        request<SuccessResult>(HttpMethod.Post, "/api/auth/logout")

    /** POST /api/auth/regenerate-recovery-key → { user, pending_recovery_key } (the key is not committed yet) */
    suspend fun regenerateRecoveryKey(): RegeneratedRecoveryKey =
        request<RegeneratedRecoveryKey>(HttpMethod.Post, "/api/auth/regenerate-recovery-key")

    /**
     * POST /api/auth/confirm-recovery-key { recoveryKey } → 200 { user, status }; 409 `not_pending`
     * arrives as [ApiError.Client] with status 409.
     */
    suspend fun confirmRecoveryKey(recoveryKey: String): ConfirmedRecoveryKey =
        request<ConfirmedRecoveryKey, RecoveryKeyRequest>(
            HttpMethod.Post,
            "/api/auth/confirm-recovery-key",
            RecoveryKeyRequest(recoveryKey),
        )

    /** PATCH /api/auth/update-timezone { timezone } → { user } */
    suspend fun updateTimezone(timezone: String): UserDto =
        request<UserEnvelope, TimezoneRequest>(HttpMethod.Patch, "/api/auth/update-timezone", TimezoneRequest(timezone)).user

    /** PATCH /api/auth/update-season-mode { season_mode } → { user } (auto/north/south/off). */
    suspend fun updateSeasonMode(seasonMode: String): UserDto =
        request<UserEnvelope, SeasonModeRequest>(
            HttpMethod.Patch,
            "/api/auth/update-season-mode",
            SeasonModeRequest(seasonMode),
        ).user

    /** PATCH /api/auth/update-notification-time { notification_time: "HH:00" } → { user } */
    suspend fun updateNotificationTime(notificationTime: String): UserDto =
        request<UserEnvelope, NotificationTimeRequest>(
            HttpMethod.Patch,
            "/api/auth/update-notification-time",
            NotificationTimeRequest(notificationTime),
        ).user

    /** DELETE /api/auth/account — удаление аккаунта (mission-scope M12; Play-требование). */
    suspend fun deleteAccount(): SuccessResult =
        request<SuccessResult>(HttpMethod.Delete, "/api/auth/account")

    // ------------------------------------------------------------------
    // Plants (8)
    // ------------------------------------------------------------------

    /** GET /api/plants → Plant[] */
    suspend fun getPlants(): List<PlantDto> =
        request<List<PlantDto>>(HttpMethod.Get, "/api/plants")

    /** POST /api/plants → созданное растение (user_id ставит сервер). */
    suspend fun addPlant(plant: InsertPlantDto): PlantDto =
        request<PlantDto, InsertPlantDto>(HttpMethod.Post, "/api/plants", plant)

    /** PATCH /api/plants/:id — частичное обновление (Patch: Absent/Value/Null). */
    suspend fun updatePlant(id: String, patch: PatchPlantDto): PlantDto =
        request<PlantDto, PatchPlantDto>(HttpMethod.Patch, "/api/plants/$id", patch)

    /** DELETE /api/plants/:id */
    suspend fun deletePlant(id: String): SuccessResult =
        request<SuccessResult>(HttpMethod.Delete, "/api/plants/$id")

    /** POST /api/plants/water-all — поливает только «должные» растения. */
    suspend fun waterAll(): MassActionResult =
        request<MassActionResult>(HttpMethod.Post, "/api/plants/water-all")

    /** POST /api/plants/postpone-all — сдвиг на 1 день, БЕЗ оптимистичности (parity). */
    suspend fun postponeAll(): MassActionResult =
        request<MassActionResult>(HttpMethod.Post, "/api/plants/postpone-all")

    // ------------------------------------------------------------------
    // Care journal (architecture §5.5)
    // ------------------------------------------------------------------

    /** GET /api/plants/:id/events?limit&before → one page, newest first. */
    suspend fun listEvents(
        plantId: String,
        limit: Int = CARE_EVENTS_PAGE_LIMIT,
        before: String? = null,
    ): CareEventsPage {
        val cursor = if (before == null) "" else "&before=$before"
        return request<CareEventsPage>(HttpMethod.Get, "/api/plants/$plantId/events?limit=$limit$cursor")
    }

    /** POST /api/plants/:id/events { type: "note", note } → 201 created event. */
    suspend fun addNote(plantId: String, note: String, occurredOn: String? = null): CareEventDto =
        request<CareEventDto, NoteRequest>(
            HttpMethod.Post,
            "/api/plants/$plantId/events",
            NoteRequest(type = CareEventType.NOTE, note = note, occurredOn = occurredOn),
        )

    /** DELETE /api/plants/:id/events/:eventId → 204; non-note 409, missing 404. */
    suspend fun deleteEvent(plantId: String, eventId: String) {
        client.request(HttpMethod.Delete, "/api/plants/$plantId/events/$eventId")
    }

    /** GET /api/plants/:id/photos → { photos } newest first. */
    suspend fun listPhotos(plantId: String): List<TimelinePhotoDto> =
        request<TimelinePhotosPage>(HttpMethod.Get, "/api/plants/$plantId/photos").photos

    /** POST /api/plants/:id/photos { photo, caption? } → 201 photo + its `photo` event. */
    suspend fun addPhoto(plantId: String, photoDataUri: String, caption: String?): CreatedTimelinePhotoDto =
        json.decodeFromString(
            decodeBody(
                client.request(
                    HttpMethod.Post,
                    "/api/plants/$plantId/photos",
                    json.encodeToString(PhotoRequest(photo = photoDataUri, caption = caption)),
                    timeoutMillis = PHOTO_UPLOAD_TIMEOUT_MILLIS,
                ),
            ),
        )

    /** DELETE /api/plants/:id/photos/:photoId → 204; the server also drops its `photo` event. */
    suspend fun deletePhoto(plantId: String, photoId: String) {
        client.request(HttpMethod.Delete, "/api/plants/$plantId/photos/$photoId")
    }

    // ------------------------------------------------------------------
    // Species
    // ------------------------------------------------------------------

    /** POST /api/species/identify { photos, lang } → { results, remainingToday }, 25 s deadline. */
    suspend fun identifySpecies(photoDataUri: String, lang: String): IdentifyResponse =
        json.decodeFromString(
            decodeBody(
                client.request(
                    HttpMethod.Post,
                    "/api/species/identify",
                    json.encodeToString(IdentifyRequest(photos = listOf(photoDataUri), lang = lang)),
                    timeoutMillis = IDENTIFY_TIMEOUT_MILLIS,
                ),
            ),
        )

    // ------------------------------------------------------------------
    // Diagnosis
    // ------------------------------------------------------------------

    /** POST /api/plants/:id/diagnose { photo, note?, language } → { causes, summary }, 75 s deadline. */
    suspend fun diagnosePlant(plantId: String, photoDataUri: String, note: String?, language: String): DiagnosisResponse =
        json.decodeFromString(
            decodeBody(
                client.request(
                    HttpMethod.Post,
                    "/api/plants/$plantId/diagnose",
                    json.encodeToString(DiagnoseRequest(photo = photoDataUri, note = note, language = language)),
                    timeoutMillis = DIAGNOSE_TIMEOUT_MILLIS,
                ),
            ),
        )

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    /** GET /api/progress → { streak, achievements } (architecture §2.11). */
    suspend fun getProgress(): ProgressDto =
        request<ProgressDto>(HttpMethod.Get, "/api/progress")

    // ------------------------------------------------------------------
    // Push (8)
    // ------------------------------------------------------------------

    /** GET /api/push/subscription → { subscribed } (web-push статус web-клиента). */
    suspend fun webPushSubscriptionStatus(): SubscriptionStatus =
        request<SubscriptionStatus>(HttpMethod.Get, "/api/push/subscription")

    /** POST /api/push/subscribe-expo { expo_push_token, language } — Expo-окно поддержки. */
    suspend fun subscribeExpo(expoPushToken: String, language: String): OkResult =
        request<OkResult, ExpoSubscribeRequest>(
            HttpMethod.Post,
            "/api/push/subscribe-expo",
            ExpoSubscribeRequest(expoPushToken, language),
        )

    /** DELETE /api/push/subscribe-expo */
    suspend fun unsubscribeExpo(): OkResult =
        request<OkResult>(HttpMethod.Delete, "/api/push/subscribe-expo")

    /** GET /api/push/expo-subscription → { subscribed } */
    suspend fun expoSubscriptionStatus(): SubscriptionStatus =
        request<SubscriptionStatus>(HttpMethod.Get, "/api/push/expo-subscription")

    /** POST /api/push/subscribe-fcm { fcm_token, platform, language } (план Stage 9). */
    suspend fun subscribeFcm(subscription: FcmSubscriptionRequest): OkResult =
        request<OkResult, FcmSubscriptionRequest>(HttpMethod.Post, "/api/push/subscribe-fcm", subscription)

    /** DELETE /api/push/subscribe-fcm */
    suspend fun unsubscribeFcm(): OkResult =
        request<OkResult>(HttpMethod.Delete, "/api/push/subscribe-fcm")

    /** GET /api/push/fcm-subscription → { subscribed } — тумблер пушей KMP-профиля. */
    suspend fun fcmSubscriptionStatus(): SubscriptionStatus =
        request<SubscriptionStatus>(HttpMethod.Get, "/api/push/fcm-subscription")

    // ------------------------------------------------------------------
    // Внутреннее
    // ------------------------------------------------------------------

    /** Запрос без тела → декод 200-тела. */
    private suspend inline fun <reified T : Any> request(
        method: HttpMethod,
        path: String,
    ): T = json.decodeFromString(decodeBody(client.request(method, path)))

    /** Запрос с телом: строка кодируется тем же json, декод 200-тела. */
    private suspend inline fun <reified T : Any, reified B : Any> request(
        method: HttpMethod,
        path: String,
        body: B,
    ): T = json.decodeFromString(decodeBody(client.request(method, path, json.encodeToString(body))))

    /** Тело ответа как текст (транспортные сбои уже промаплены в ApiError). */
    private suspend fun decodeBody(response: HttpResponse): String = response.bodyAsText()

    // Тела-запросы (внутренние JSON-обёртки)
    @kotlinx.serialization.Serializable
    private data class NameBody(val name: String?)

    @kotlinx.serialization.Serializable
    private data class RecoveryKeyRequest(@kotlinx.serialization.SerialName("recoveryKey") val recoveryKey: String)

    @kotlinx.serialization.Serializable
    private data class TimezoneRequest(val timezone: String)

    @kotlinx.serialization.Serializable
    private data class SeasonModeRequest(@kotlinx.serialization.SerialName("season_mode") val seasonMode: String)

    @kotlinx.serialization.Serializable
    private data class NotificationTimeRequest(@kotlinx.serialization.SerialName("notification_time") val notificationTime: String)

    @kotlinx.serialization.Serializable
    private data class NoteRequest(
        val type: String,
        val note: String,
        @kotlinx.serialization.SerialName("occurred_on") val occurredOn: String? = null,
    )

    @kotlinx.serialization.Serializable
    private data class PhotoRequest(val photo: String, val caption: String? = null)

    @kotlinx.serialization.Serializable
    private data class ExpoSubscribeRequest(@kotlinx.serialization.SerialName("expo_push_token") val expoPushToken: String, val language: String)
}

/**
 * [PushSubscriptions] поверх [GreenThumbApi] (M9 push-android): шов сети для
 * [site.xmpp.greenthumb.core.platform.PushTokens]. Ошибки НЕ глотаются —
 * [ApiError] наверх (тумблер профиля показывает сообщение, RN-паритет).
 *
 * [PushSubscriptions.subscribe]'овский `platform` (строка провода) мапится в
 * [PlatformDto]; неизвестное значение — фолбэк `android` (паритет серверного
 * fallback из backend-contract.md №24).
 */
public class FcmPushSubscriptions(private val api: GreenThumbApi) : PushSubscriptions {
    override suspend fun subscribe(token: String, platform: String, language: String) {
        val wire = PlatformDto.entries.firstOrNull { it.name == platform } ?: PlatformDto.android
        api.subscribeFcm(FcmSubscriptionRequest(fcmToken = token, platform = wire, language = language))
    }

    override suspend fun unsubscribe() {
        api.unsubscribeFcm()
    }

    override suspend fun status(): Boolean = api.fcmSubscriptionStatus().subscribed
}
