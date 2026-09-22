package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * Платформенный движок Ktor: okhttp на Android, cio на JVM (architecture.md §5).
 * expect объявлен в commonMain; actual'ы — androidMain/jvmMain.
 */
internal expect fun platformEngineFactory(): HttpClientEngineFactory<*>

/**
 * Public-фабрика движка для точек входа (MainActivity, desktop main.kt):
 * ktor-типы наружу не выставляются — entry points не знают ktor вообще.
 */
public object PlatformEngine {
    public fun default(): HttpClientEngineFactory<*> = platformEngineFactory()
}
