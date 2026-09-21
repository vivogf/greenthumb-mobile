package site.xmpp.greenthumb.desktop

import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.core.network.UserDto

/**
 * Ручной прогон VAL-NET-007 (вне гейта; запускается только вручную):
 * логин тестового аккаунта по ключу (env KMP_RECOVERY_KEY), реальный
 * GET /api/plants разбирается в модели. Печатает зафиксированный
 * фактический тип id/user_id с провода.
 *
 * Запуск из IDE/Gradle: `KMP_RECOVERY_KEY=<ключ> ... RealApiProbeKt`.
 * Внешние jar-зависимости (ktor/stdlib/serialization/slf4j) не fat-jar:
 * проще запускать через `:desktopApp:installDist` (bin/ с полным cp),
 * чем собирать -cp руками — при ручной сборке cp дубликаты ktor-jar
 * (грубый find по кэшу) дают ложный ApiError.Network.
 */
fun main() {
    val recoveryKey = System.getenv("KMP_RECOVERY_KEY") ?: error("KMP_RECOVERY_KEY не задан")
    val client = ApiClient(io.ktor.client.engine.cio.CIO)
    val api = GreenThumbApi(client)
    runBlocking {
        val user: UserDto = api.loginRecovery(recoveryKey)
        println("LOGIN OK: id=${user.id} name=${user.name}")
        val plants: List<PlantDto> = api.getPlants()
        println("PLANTS COUNT=${plants.size}")
        plants.forEach { p ->
            println("PLANT id=${p.id} userId=${p.userId} name=${p.name} water=${p.waterFrequencyDays} last=${p.lastWateredDate}")
        }
    }
    client.close()
}
