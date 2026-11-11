package site.xmpp.greenthumb.core.platform

import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * Android-actual: AccessibiityManager системного сервиса. «Включён экранный диктор» = запущена служба
 * доступности и включено обзорное касание (обычный режим TalkBack). Контекст — из того же реестра, что у
 * [Clipboard] ([registerAppContext] в Application.onCreate). Без контекста — false: таймеры остаются обычными.
 */
public actual object ScreenReader {
    public actual fun isOn(): Boolean {
        val context = appContextOrNull() ?: return false
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        return runCatching { manager.isEnabled && manager.isTouchExplorationEnabled }.getOrDefault(false)
    }
}
