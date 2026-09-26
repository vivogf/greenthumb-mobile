package site.xmpp.greenthumb.core.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context

/**
 * Android-actual буфера обмена: ClipboardManager системного сервиса.
 * MIME textPlain — как RN `expo-clipboard` (`setStringAsync`).
 *
 * Контекст берётся из того же реестра, что [site.xmpp.greenthumb.core.storage.isUpdateInstall]
 * ([registerAppContext] в Application.onCreate — GreenThumbApplication).
 * Sensitive-флаг не ставится: RN-оригинал копировал без него (ClipData
 * без EXTRA_IS_SENSITIVE), паритет сохранён.
 */
public actual object Clipboard {
    public actual fun copy(value: String) {
        val context = appContextOrNull() ?: return
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            manager.setPrimaryClip(
                ClipData.newPlainText(ClipDescription.MIMETYPE_TEXT_PLAIN, value),
            )
        }
    }
}
