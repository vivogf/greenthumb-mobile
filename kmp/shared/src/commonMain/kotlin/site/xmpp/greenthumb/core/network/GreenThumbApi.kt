package site.xmpp.greenthumb.core.network

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.serialization.StringFormat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

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

    /** POST /api/auth/regenerate-recovery-key → { user } с новым ключом */
    suspend fun regenerateRecoveryKey(): UserDto =
        request<UserEnvelope>(HttpMethod.Post, "/api/auth/regenerate-recovery-key").user

    /** PATCH /api/auth/update-timezone { timezone } → { user } */
    suspend fun updateTimezone(timezone: String): UserDto =
        request<UserEnvelope, TimezoneRequest>(HttpMethod.Patch, "/api/auth/update-timezone", TimezoneRequest(timezone)).user

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
    private data class NotificationTimeRequest(@kotlinx.serialization.SerialName("notification_time") val notificationTime: String)

    @kotlinx.serialization.Serializable
    private data class ExpoSubscribeRequest(@kotlinx.serialization.SerialName("expo_push_token") val expoPushToken: String, val language: String)
}
