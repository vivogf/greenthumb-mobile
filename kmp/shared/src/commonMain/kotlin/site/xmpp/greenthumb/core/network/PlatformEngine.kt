package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.HttpClientEngineFactory

/**
 * Платформенный движок Ktor: okhttp на Android, cio на JVM (architecture.md §5).
 * expect объявлен в commonMain; actual'ы — androidMain/jvmMain.
 */
internal expect fun platformEngineFactory(): HttpClientEngineFactory<*>
