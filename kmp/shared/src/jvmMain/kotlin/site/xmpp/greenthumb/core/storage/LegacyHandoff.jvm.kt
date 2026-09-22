package site.xmpp.greenthumb.core.storage

/**
 * JVM-actual LegacyHandoff для desktop-харнесса (architecture.md §6):
 * всегда null / no-op — desktop-приложение Expo-сборкой никогда не было,
 * файлу там взяться неоткуда (фича kmp-legacy-handoff; VAL-HANDOFF-IMP-004
 * покрывается jvmTest-ами общего парсера).
 */
public actual class LegacyHandoff actual constructor(appContext: Any) : HandoffSource {
    actual override fun readHandoff(): HandoffPayload? = null

    actual override fun clearHandoff() = Unit
}
