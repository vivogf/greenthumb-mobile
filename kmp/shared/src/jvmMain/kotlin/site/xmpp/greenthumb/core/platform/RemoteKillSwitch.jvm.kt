package site.xmpp.greenthumb.core.platform

/**
 * Desktop-актуал kill-switch: Firebase на JVM нет (architecture.md §0) —
 * заглушка «лог + значение по умолчанию» (план Stage 12 п.1). Выключатель
 * на харнессе не активен: [DEFAULT_MIN_SUPPORTED_BUILD] = 0 всегда меньше
 * [AppVersion.code] → экран обновления в обычном desktop-потоке не показывается.
 *
 * Дев-ручка hotRun (правило «экран, не открывающийся в харнессе, не закончен»):
 * env GT_MIN_SUPPORTED_BUILD подменяет значение — desktop-сборка с
 * GT_MIN_SUPPORTED_BUILD=999999 открывает блокирующий экран без Firebase.
 * На Android-актуал не влияет (там свой оверрайд debug-файлом).
 */
public actual open class RemoteKillSwitch {
    public actual constructor()

    public actual open suspend fun fetchMinimumSupportedBuild(): Long {
        val override = System.getenv(ENV_OVERRIDE)?.trim()?.toLongOrNull()
        if (override != null) {
            println("[RemoteKillSwitch] dev override $ENV_OVERRIDE=$override (desktop harness)")
            return override
        }
        println(
            "[RemoteKillSwitch] desktop stub: no Firebase on JVM — kill switch inactive " +
                "($DEFAULT_MIN_SUPPORTED_BUILD)",
        )
        return DEFAULT_MIN_SUPPORTED_BUILD
    }

    private companion object {
        /** Имя env-ручки дев-поверхности харнесса. */
        const val ENV_OVERRIDE = "GT_MIN_SUPPORTED_BUILD"
    }
}
