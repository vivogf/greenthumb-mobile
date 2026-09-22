package site.xmpp.greenthumb.core.storage

import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VAL-HANDOFF-IMP-004: разбор handoff-файла Stage 0 устойчив к мусору —
 * неизвестная `v`, битый JSON, пустой recovery_key трактуются как
 * «handoff отсутствует», файл НЕ удаляется. Валидный документ разбирается
 * в HandoffPayload со всеми полями.
 *
 * Парсер (HandoffPayload.parse) — общий, тестируется на jvm; android-actual
 * (filesDir) подтверждается VAL-HANDOFF-IMP-001 на эмуляторе.
 */
@OptIn(ExperimentalPathApi::class)
class LegacyHandoffJvmTest {

    private val storageDir = createTempDirectory(prefix = "gt-legacy-handoff")
    private val handoffFile = storageDir / HANDOFF_FILE_NAME

    @AfterTest
    fun cleanup() {
        storageDir.deleteRecursively()
    }

    // -----------------------------------------------------------------
    // Мусор → null, файл не тронут (VAL-HANDOFF-IMP-004)
    // -----------------------------------------------------------------

    @Test
    fun unknown_schema_version_reads_as_absent_and_file_survives() {
        handoffFile.writeText("""{"v":2,"recovery_key":"key-42"}""")

        assertNull(HandoffPayload.parse(handoffFile.readText()))
        assertTrue(handoffFile.exists())
        assertEquals("""{"v":2,"recovery_key":"key-42"}""", handoffFile.readText())
    }

    @Test
    fun missing_schema_version_reads_as_absent() {
        handoffFile.writeText("""{"recovery_key":"key-42"}""")

        assertNull(HandoffPayload.parse(handoffFile.readText()))
        assertTrue(handoffFile.exists())
    }

    @Test
    fun corrupted_json_reads_as_absent_and_file_survives() {
        handoffFile.writeText("""{"v":1,"recovery_key":"broken""")

        assertNull(HandoffPayload.parse(handoffFile.readText()))
        assertTrue(handoffFile.exists())
        assertFalse(handoffFile.readBytes().isEmpty())
    }

    @Test
    fun arbitrary_binary_garbage_reads_as_absent_and_file_survives() {
        val garbage = byteArrayOf(0, 1, 2, 0x7F, 0x21, 0x09, 0x00, 3)
        handoffFile.writeBytes(garbage)

        assertNull(HandoffPayload.parse(handoffFile.readText()))
        assertTrue(handoffFile.exists())
        assertEquals(garbage.toList(), handoffFile.readBytes().toList())
    }

    @Test
    fun json_non_object_reads_as_absent() {
        handoffFile.writeText("""["v",1]""")

        assertNull(HandoffPayload.parse(handoffFile.readText()))
        assertTrue(handoffFile.exists())
    }

    @Test
    fun empty_recovery_key_reads_as_absent_and_file_survives() {
        handoffFile.writeText("""{"v":1,"recovery_key":""}""")

        assertNull(HandoffPayload.parse(handoffFile.readText()))
        assertTrue(handoffFile.exists())
    }

    @Test
    fun blank_recovery_key_reads_as_absent_and_file_survives() {
        handoffFile.writeText("""{"v":1,"recovery_key":"   "}""")

        assertNull(HandoffPayload.parse(handoffFile.readText()))
        assertTrue(handoffFile.exists())
    }

    @Test
    fun non_string_recovery_key_reads_as_absent() {
        handoffFile.writeText("""{"v":1,"recovery_key":42}""")

        assertNull(HandoffPayload.parse(handoffFile.readText()))
        assertTrue(handoffFile.exists())
    }

    @Test
    fun null_recovery_key_reads_as_absent() {
        handoffFile.writeText("""{"v":1,"recovery_key":null}""")

        assertNull(HandoffPayload.parse(handoffFile.readText()))
        assertTrue(handoffFile.exists())
    }

    @Test
    fun missing_recovery_key_reads_as_absent() {
        handoffFile.writeText("""{"v":1}""")

        assertNull(HandoffPayload.parse(handoffFile.readText()))
        assertTrue(handoffFile.exists())
    }

    // -----------------------------------------------------------------
    // Валидный документ → HandoffPayload со всеми полями
    // -----------------------------------------------------------------

    @Test
    fun valid_document_parses_all_fields() {
        val json =
            """{"v":1,"recovery_key":"gtk_probe_key_42","language":"ru",""" +
                """"theme":"dark","layout_mode":"card","intro_seen":true}"""

        assertEquals(
            HandoffPayload(
                recoveryKey = "gtk_probe_key_42",
                language = AppLanguage.Ru,
                theme = ThemePreference.Dark,
                layoutMode = LayoutMode.Card,
                introSeen = true,
            ),
            HandoffPayload.parse(json),
        )
    }

    @Test
    fun valid_document_without_optional_settings_parses_with_nulls() {
        // Мусор в настройках / их отсутствие → null: ключ всё равно
        // переносится, applyLegacyHandoffValues(null) ничего не перезапишет.
        val json = """{"v":1,"recovery_key":"key-42","language":"fr","theme":"system"}"""

        assertEquals(
            HandoffPayload(recoveryKey = "key-42"),
            HandoffPayload.parse(json),
        )
    }

    @Test
    fun intro_seen_non_boolean_reads_as_false() {
        val json = """{"v":1,"recovery_key":"key-42","intro_seen":"1"}"""

        assertEquals(
            HandoffPayload(recoveryKey = "key-42", introSeen = false),
            HandoffPayload.parse(json),
        )
    }

    @Test
    fun all_wire_values_roundtrip() {
        for (language in listOf("en", "ru")) {
            for (theme in listOf("auto", "light", "dark")) {
                for (layoutMode in listOf("list", "card", "grid")) {
                    val json =
                        """{"v":1,"recovery_key":"key-42","language":"$language",""" +
                            """"theme":"$theme","layout_mode":"$layoutMode","intro_seen":true}"""
                    val payload = HandoffPayload.parse(json)
                    assertEquals(AppLanguage.fromWire(language), payload?.language)
                    assertEquals(ThemePreference.fromWire(theme), payload?.theme)
                    assertEquals(LayoutMode.fromWire(layoutMode), payload?.layoutMode)
                    assertTrue(payload!!.introSeen)
                }
            }
        }
    }

    @Test
    fun jvm_actual_reads_null_and_clear_is_noop() {
        val handoff = LegacyHandoff(Any())

        // JVM-харнесс никогда не был Expo-приложением: файла нет даже
        // теоретически (файл в temp-каталоге теста — для чистоты парсера).
        handoffFile.writeText("""{"v":1,"recovery_key":"key-42"}""")
        assertNull(handoff.readHandoff())
        // Идемпотентный no-op, не бросает.
        handoff.clearHandoff()
        assertTrue(handoffFile.exists())
    }
}
