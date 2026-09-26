package site.xmpp.greenthumb.core.platform

import android.content.Intent
import android.net.Uri

/**
 * Android-actual: ACTION_VIEW на Uri (RN Linking.openURL → intent VIEW).
 * Активность не указана (non-activity context) — флаг NEW_TASK обязателен.
 * Нет обработчика схемы (ActivityNotFoundException) — молча: RN тоже
 * fire-and-forget.
 */
public actual object OpenUrl {
    public actual fun open(url: String) {
        val context = appContextOrNull() ?: return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
