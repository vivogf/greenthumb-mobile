package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Android-движок Ktor: okhttp (пин Ktor 3.5.2, architecture.md §5).
 * OkHttp сам владеет соединительным пулом и HTTP/2; таймауты управляются
 * плагином HttpTimeout на уровне Ktor.
 */
internal actual fun platformEngineFactory(): HttpClientEngineFactory<*> = OkHttp

/** Android: прод-RN-значение, без override (env-override — desktop-механика). */
public actual val API_BASE_URL: String = "https://greenthumb.xmpp.site"
