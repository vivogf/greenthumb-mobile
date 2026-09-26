package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

/**
 * JVM-движок Ktor: cio (пин Ktor 3.5.2, architecture.md §5).
 * Используется desktop-харнессом (Compose Hot Reload) и jvmTest.
 */
internal actual fun platformEngineFactory(): HttpClientEngineFactory<*> = CIO

/**
 * Базовый URL для JVM: прод-RN-значение по умолчанию; env `GT_BASE_URL`
 * переопределяет для desktop-харнесса (MCP-верификация против локального
 * стенда, не прод) и jvmTest-прогонов. Не-заданный env — прод.
 */
public actual val API_BASE_URL: String =
    System.getenv("GT_BASE_URL") ?: "https://greenthumb.xmpp.site"
