package site.xmpp.greenthumb.core.platform

/**
 * Семантические тактильные события — один к одному стилям expo-haptics в RN:
 * `impactAsync(Light/Medium)` → [Light]/[Medium], `selectionAsync()` → [Selection],
 * `notificationAsync(Success/Error)` → [Success]/[Error]. Точки вызовов RN:
 * welcome (next/finish), login (создание/вход/копия ключа), enable-notifications
 * (enable/later), profile (пуши/время/копия/регенерация/язык/тема), кнопка полива
 * (Medium внутри WaterButtonWithParticles).
 */
public enum class HapticEvent { Light, Medium, Selection, Success, Error }

/**
 * Платформенный исполнитель события. Отказ/отсутствие вибратора не доходит
 * до вызывающего: экран и действие не ломаются без вибромотора (VAL-ANIM-004).
 */
public fun interface HapticSink {
    public fun perform(event: HapticEvent)
}

/** Android-actual — Vibrator с predefined-эффектами; JVM-харнесс — no-op. */
public expect fun platformHapticSink(): HapticSink

/**
 * Маршрутизатор тактильных событий (RN `Haptics.*Async` — глобальные вызовы,
 * не меняют навигацию и данные; здесь то же: fire-and-forget в платформенный
 * [sink]).
 *
 * [sink] заменяем для тестов маршрутизации (injected recorder) — прод-код
 * его не трогает.
 */
public object Haptics {
    @Volatile
    public var sink: HapticSink = platformHapticSink()

    public fun light() {
        sink.perform(HapticEvent.Light)
    }

    public fun medium() {
        sink.perform(HapticEvent.Medium)
    }

    public fun selection() {
        sink.perform(HapticEvent.Selection)
    }

    public fun success() {
        sink.perform(HapticEvent.Success)
    }

    public fun error() {
        sink.perform(HapticEvent.Error)
    }
}
