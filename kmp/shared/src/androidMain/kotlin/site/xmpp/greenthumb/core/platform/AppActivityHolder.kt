package site.xmpp.greenthumb.core.platform

import androidx.activity.ComponentActivity

/**
 * Текущая Activity для activity-result-сервисов (пикер изображения Stage 8).
 * Пишется из `MainActivity.onCreate`/`onDestroy`; до регистрации — null, и
 * [pickImage] безопасно возвращает `Cancelled` (никаких падений при
 * пересоздании Activity). Application-контекста ([AppContextHolder]) для
 * `registerForActivityResult` недостаточно — registry живёт на Activity.
 */
object AppActivityHolder {
    @Volatile
    var activity: ComponentActivity? = null
}
