package site.xmpp.greenthumb.core.platform

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Android-actual буфера обмена: ClipboardManager системного сервиса.
 * MIME textPlain — как RN `expo-clipboard` (`setStringAsync`).
 *
 * Контекст берётся из того же реестра, что [site.xmpp.greenthumb.core.storage.isUpdateInstall]
 * ([registerAppContext] в Application.onCreate — GreenThumbApplication).
 * Every copy is the recovery key, so the clip is marked sensitive: Android 13+ hides it in
 * the clipboard preview. Older versions get the same extra by its string name.
 */
public actual object Clipboard {
    public actual fun copy(value: String) {
        val context = appContextOrNull() ?: return
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = ClipData.newPlainText(ClipDescription.MIMETYPE_TEXT_PLAIN, value)
            clip.description.extras = PersistableBundle().apply {
                putBoolean(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ClipDescription.EXTRA_IS_SENSITIVE
                    } else {
                        "android.content.extra.IS_SENSITIVE"
                    },
                    true,
                )
            }
            manager.setPrimaryClip(clip)
        }
    }
}
