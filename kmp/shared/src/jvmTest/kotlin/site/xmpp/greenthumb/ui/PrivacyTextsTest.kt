package site.xmpp.greenthumb.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The start-screen and Profile privacy texts must state what the release really does
 * (the privacy page in docs/ is the long form). The start screen carries the short form of the
 * owner mockup (M10 D§1 item 2); Profile keeps every fact of the long form.
 */
class PrivacyTextsTest {

    private fun strings(dir: String): Map<String, String> {
        val file = File("src/commonMain/composeResources/$dir/strings.xml")
        val re = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return re.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val aboutFacts = mapOf(
        "values" to listOf("name is optional", "crash", "ANR", "session event", "no logs about you", "only as a hash", "backup"),
        "values-ru" to listOf("Имя необязательно", "падениях", "ANR", "событие сеанса", "не ведёт журналов", "только в виде хеша", "резервных копий"),
    )

    /** No ads, no analytics, no visit logs, the server keeps only a fingerprint, anonymous crash reports. */
    private val shortFacts = mapOf(
        "values" to listOf("No ads", "analytics", "visit logs", "only its fingerprint", "cannot rebuild the key", "Crash reports are anonymous"),
        "values-ru" to listOf("Без рекламы", "аналитики", "журналов посещений", "только его отпечаток", "ключ не восстановить", "Отчёты о сбоях анонимные"),
    )

    private val loginTexts = mapOf(
        "values" to mapOf(
            "privacy_loginHint" to "No ads, analytics or visit logs. Your sign-in key stays with you — the server keeps only its fingerprint, which cannot rebuild the key. Crash reports are anonymous.",
            "privacy_moreLink" to "More about data →",
            "privacy_contactLead" to "If something breaks, write to:",
        ),
        "values-ru" to mapOf(
            "privacy_loginHint" to "Без рекламы, аналитики и журналов посещений. Ключ входа хранится у вас — сервер помнит только его отпечаток, по которому ключ не восстановить. Отчёты о сбоях анонимные.",
            "privacy_moreLink" to "Подробнее о данных →",
            "privacy_contactLead" to "Если что-то сломалось — напишите:",
        ),
    )

    @Test
    fun login_hint_states_short_facts() {
        for ((dir, phrases) in shortFacts) {
            val text = strings(dir).getValue("privacy_loginHint")
            for (phrase in phrases) assertTrue(text.contains(phrase, ignoreCase = true), "$dir/privacy_loginHint misses \"$phrase\": $text")
        }
    }

    @Test
    fun login_texts_match_the_mockup_exactly() {
        for ((dir, expected) in loginTexts) {
            val s = strings(dir)
            for ((key, text) in expected) assertEquals(text, s[key], "$dir/$key")
        }
    }

    @Test
    fun about_body_keeps_seven_facts() {
        for ((dir, phrases) in aboutFacts) {
            val text = strings(dir).getValue("privacy_aboutBody")
            for (phrase in phrases) {
                assertTrue(text.contains(phrase, ignoreCase = true), "$dir/privacy_aboutBody misses \"$phrase\": $text")
            }
        }
    }

    @Test
    fun no_string_tells_the_user_to_reinstall() {
        for (dir in aboutFacts.keys) {
            val bad = strings(dir).filterValues { Regex("reinstall|переустанов", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            assertFalse(bad.isNotEmpty(), "$dir: $bad")
        }
    }
}
