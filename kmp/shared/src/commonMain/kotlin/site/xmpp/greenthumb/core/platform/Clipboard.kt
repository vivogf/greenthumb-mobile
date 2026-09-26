package site.xmpp.greenthumb.core.platform

/**
 * Буфер обмена (architecture.md §3 «Clipboard», план Stage 9 «14 expect/actual
 * минус iOS» — первое потребление фичей screen-login M7, порт RN
 * `expo-clipboard` в `app/(auth)/login.tsx:copyKey`).
 *
 * Поставщик строки — recovery key: копирование на show-key (RN copyKey) и
 * позже на экране профиля. Отказ/сбой не блокирует флоу: экран показывает
 * «скопировано» по успеху (RN setCopied(true) — тоже без обработки отказа).
 *
 * androidMain — ClipboardManager (системный сервис); jvmMain —
 * java.awt.datatransfer (Toolkit-контекст создаётся один раз; харнесс).
 */
public expect object Clipboard {
    /** Записать [value] в буфер обмена (RN Clipboard.setStringAsync). */
    public fun copy(value: String)
}
