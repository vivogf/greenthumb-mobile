package site.xmpp.greenthumb.data

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.encodeToString
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.core.storage.JvmPlantDatabases
import java.io.File
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively

internal const val USER: String = "24"

/**
 * Пояс тестового «сегодня» — UTC+3 (Europe/Moscow), НЕ [TimeZone.UTC]:
 * UTC в статусе полива сдвинул бы его на сутки у большинства пользователей
 * (Stage 11 п.2; `lib/utils.ts:8-19` — локальная дата). 09:00Z = полдень
 * по [TEST_ZONE] → локальный день 2026-09-25, метка баннера «12:00».
 */
internal val TEST_ZONE: TimeZone = TimeZone.of("Europe/Moscow")

internal val SYNCED_AT: Long = Instant.parse("2026-09-25T09:00:00Z").toEpochMilliseconds()

internal const val TODAY: String = "2026-09-25"

internal val JSON_HEADERS = headersOf("Content-Type", "application/json")

internal fun plant(
    id: String,
    lastWatered: String,
    frequency: Int = 7,
    name: String = id,
): PlantDto = PlantDto(
    id = id,
    userId = USER,
    name = name,
    location = "shelf",
    photoUrl = "",
    waterFrequencyDays = frequency,
    lastWateredDate = lastWatered,
    notes = "",
    createdAt = "2026-01-01T00:00:00.000Z",
)

/** Просрочено на 2026-09-25 при частоте 7. */
internal fun overduePlant(): PlantDto = plant("overdue", "2026-09-01")

/** Полив сегодня (статус today, не healthy). */
internal fun dueTodayPlant(): PlantDto = plant("due", "2026-09-18")

/** Ещё не пора. */
internal fun healthyPlant(): PlantDto = plant("healthy", "2026-09-24")

internal fun plantsJson(plants: List<PlantDto>): String = ApiClient.json.encodeToString(plants)

internal fun plantJson(plant: PlantDto): String = ApiClient.json.encodeToString(plant)

internal fun MockRequestHandleScope.ok(body: String) = respond(body, HttpStatusCode.OK, JSON_HEADERS)

internal fun MockRequestHandleScope.serverError() =
    respond("""{"error":"fail"}""", HttpStatusCode.InternalServerError, JSON_HEADERS)

internal fun requestBody(content: Any): String? = when (content) {
    is TextContent -> content.text
    else -> null
}

@OptIn(ExperimentalPathApi::class)
internal class RepoHarness(
    val dir: File,
    val databases: JvmPlantDatabases,
    val client: ApiClient,
    val repo: PlantRepository,
) : AutoCloseable {
    override fun close() {
        runCatching { repo.close() }
        runCatching { client.close() }
        dir.toPath().deleteRecursively()
    }
}

@OptIn(ExperimentalPathApi::class)
internal fun openHarness(
    nowMillis: () -> Long = { SYNCED_AT },
    ids: ArrayDeque<String> = ArrayDeque(),
    session: AccountSession = AccountSession(),
    zone: TimeZone = TEST_ZONE,
    handler: MockRequestHandler,
): RepoHarness {
    val dir = createTempDirectory(prefix = "gt-repo").toFile()
    val databases = JvmPlantDatabases(dir)
    val client = ApiClient(io.ktor.client.engine.mock.MockEngine(handler), NoSessionRecoveryProvider, session)
    val api = GreenThumbApi(client)
    var n = 0
    val repo = PlantRepository(
        userId = USER,
        db = databases.open(USER),
        api = api,
        deleteFiles = { id -> databases.delete(id) },
        clocks = PlantClocks(nowMillis = nowMillis, zone = zone),
        newId = {
            if (ids.isEmpty()) "m-${++n}" else ids.removeFirst()
        },
        session = session,
    )
    return RepoHarness(dir, databases, client, repo)
}

/**
 * Второй инстанс на том же файле — «следующий старт». Предыдущий репозиторий
 * обязан быть закрыт: два открытых Room на один файл не поддерживаются.
 */
internal fun reopenRepository(
    databases: JvmPlantDatabases,
    handler: MockRequestHandler,
    nowMillis: () -> Long = { SYNCED_AT },
    zone: TimeZone = TEST_ZONE,
): Pair<ApiClient, PlantRepository> {
    val client = ApiClient(io.ktor.client.engine.mock.MockEngine(handler))
    var n = 0
    val repo = PlantRepository(
        userId = USER,
        db = databases.open(USER),
        api = GreenThumbApi(client),
        deleteFiles = { id -> databases.delete(id) },
        clocks = PlantClocks(nowMillis = nowMillis, zone = zone),
        newId = { "r-${++n}" },
    )
    return client to repo
}
