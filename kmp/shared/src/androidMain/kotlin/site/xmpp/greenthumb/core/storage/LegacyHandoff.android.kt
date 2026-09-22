package site.xmpp.greenthumb.core.storage

import android.content.Context
import java.io.File

/**
 * Android-actual LegacyHandoff (architecture.md §6): читает
 * filesDir/gt-handoff.json — файл, оставленный Expo-приложением Stage 0
 * (expo-file-system Paths.document = filesDir, проверено
 * library/expo-filesystem-api-verified.md на эмуляторе, фича
 * expo-handoff-release-code).
 *
 * - Нет файла → null («handoff отсутствует», VAL-HANDOFF-IMP-003);
 * - Нечитаем/мусор → null, файл НЕ удаляется: мусор в этом файле —
 *   потенциально единственная копия recovery key, удалять до проверенной
 *   записи в SecureStore нельзя (VAL-HANDOFF-IMP-004);
 * - clearHandoff — идемпотентное удаление (no-op при отсутствии), только
 *   после проверенной записи ключа (VAL-HANDOFF-IMP-005).
 *
 * Context — applicationContext androidApp-активности (инжектируется точкой
 * входа Stage 6; до неё actual создаётся лениво из текущего процесса).
 */
public actual class LegacyHandoff(
    appContext: Context,
) {
    private val appContext = appContext.applicationContext

    actual fun readHandoff(): HandoffPayload? {
        val file = handoffFile()
        if (!file.exists()) return null
        val content = runCatching { file.readText() }.getOrNull() ?: return null
        return HandoffPayload.parse(content)
    }

    actual fun clearHandoff() {
        handoffFile().takeIf { it.exists() }?.delete()
    }

    private fun handoffFile(): File = File(appContext.filesDir, HANDOFF_FILE_NAME)
}
