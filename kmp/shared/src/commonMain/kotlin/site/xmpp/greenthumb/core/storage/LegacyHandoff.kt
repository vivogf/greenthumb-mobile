package site.xmpp.greenthumb.core.storage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Имя handoff-файла Stage 0 (RN lib/storage.ts HANDOFF_FILE_NAME, значение
 * 1:1). Expo пишет его в Paths.document — на Android это filesDir
 * (library/expo-filesystem-api-verified.md), android-actual читает то же
 * место.
 */
public const val HANDOFF_FILE_NAME: String = "gt-handoff.json"

/**
 * Содержимое gt-handoff.json — схема Stage 0 (lib/storage.ts, версия 1):
 *
 * ```
 * {"v":1,"recovery_key":"…","language":"ru","theme":"dark",
 *  "layout_mode":"card","intro_seen":true}
 * ```
 *
 * recovery_key — единственный невосстановимый креденшл приложения
 * (docs/privacy.html: провайдер ключ не восстанавливает). Настройки —
 * значения из RN AsyncStorage: напрямую его не читать (на Android это
 * SQLite RKStorage), настройки приезжают только через handoff.
 *
 * Разбор — [parse]; предпочтения толерантны к мусору: неизвестные/не-строковые
 * значения → null («не передано», AppSettings оставит свои дефолты), ключ —
 * строго непустая строка. Мусорный документ целиком → null из [parse]:
 * «handoff отсутствует», файл при этом НЕ удаляется (VAL-HANDOFF-IMP-004).
 */
public data class HandoffPayload(
    public val recoveryKey: String,
    public val language: AppLanguage? = null,
    public val theme: ThemePreference? = null,
    public val layoutMode: LayoutMode? = null,
    public val introSeen: Boolean = false,
) {
    public companion object {

        /**
         * Разбор содержимого handoff-файла. Возвращает null (= «handoff
         * отсутствует») для:
         * - не-JSON / битого JSON / JSON не-объекта (массив, число, строка);
         * - неизвестной версии [SCHEMA_VERSION] (включая отсутствующую v);
         * - отсутствующего, не-строкового, пустого или whitespace-only
         *   recovery_key.
         *
         * Валидный документ без полей настроек (или с мусорными значениями)
         * разбирается с null-настройками: ключ переносится, отсутствующие
         * значения [AppSettings.applyLegacyHandoffValues] не перезаписывает.
         */
        public fun parse(json: String): HandoffPayload? {
            val root = runCatching { settingsJson.parseToJsonElement(json) }
                .getOrNull() as? JsonObject ?: return null

            val version = (root[KEY_V] as? JsonPrimitive)?.intOrNull
            if (version != SCHEMA_VERSION) return null

            // Ключ — строго строка: число/булево/null в этом поле = мусор.
            val recoveryKey = (root[KEY_RECOVERY_KEY] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            if (recoveryKey.isNullOrBlank()) return null

            return HandoffPayload(
                recoveryKey = recoveryKey,
                language = AppLanguage.fromWire(root.stringOrAbsent(KEY_LANGUAGE)),
                theme = ThemePreference.fromWire(root.stringOrAbsent(KEY_THEME)),
                layoutMode = LayoutMode.fromWire(root.stringOrAbsent(KEY_LAYOUT_MODE)),
                // Не-boolean/отсутствие = false: худший случай — интро
                // переиграется один раз (как в RN getHasSeenIntro: catch → false).
                introSeen = (root[KEY_INTRO_SEEN] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }

        /** Единственная поддержанная версия схемы handoff (Stage 0). */
        private const val SCHEMA_VERSION: Int = 1

        private const val KEY_V = "v"
        private const val KEY_RECOVERY_KEY = "recovery_key"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_THEME = "theme"
        private const val KEY_LAYOUT_MODE = "layout_mode"
        private const val KEY_INTRO_SEEN = "intro_seen"

        /** Строка или «отсутствует»: JsonNull/не-примитив → null. */
        private fun JsonObject.stringOrAbsent(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull
    }
}

/**
 * expect: чтение/удаление handoff-файла Stage 0 — architecture.md §6,
 * VAL-HANDOFF-IMP-001..004.
 *
 * Контракт:
 * - [readHandoff] — [HandoffPayload] или null; null = «handoff отсутствует»
 *   (нет файла, нечитаем, мусор по правилам [HandoffPayload.parse]). Чтение
 *   чистое: мусорный файл НЕ удаляется — единственная копия ключа не теряется
 *   до проверенной записи в SecureStore (VAL-HANDOFF-IMP-004);
 * - [clearHandoff] — идемпотентное удаление файла. Вызывается ТОЛЬКО после
 *   проверенной записи ключа+настроек (перечитанный SecureStore совпал):
 *   при провале записи файл обязан остаться на диске (VAL-HANDOFF-IMP-005).
 *
 * Платформенные actual'ы:
 * - androidMain: filesDir/gt-handoff.json — файл, оставленный Expo-приложением
 *   (Stage 0);
 * - jvmMain: всегда null / no-op — desktop-харнесс Expo-приложением не был,
 *   файлу там взяться неоткуда (iOS — вне скоупа миссии, ios-actual по той же
 *   причине вернёт null).
 *
 * Файловый IO — вызывать вне main-потока (стартовая последовательность Stage 6
 * крутится в корутине на IO/Default).
 */
public expect class LegacyHandoff {
    public fun readHandoff(): HandoffPayload?

    public fun clearHandoff()
}
