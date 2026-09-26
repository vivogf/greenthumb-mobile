package site.xmpp.greenthumb.core.platform

import java.awt.Desktop
import java.net.URI

/**
 * Desktop-actual: java.awt.Desktop.browse (харнесс). Headless/нет обработчика
 * схемы (desktop-support для mailto) — runCatching глотает: ссылка поддержки
 * не блокирует флоу.
 */
public actual object OpenUrl {
    public actual fun open(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            }
        }
    }
}
