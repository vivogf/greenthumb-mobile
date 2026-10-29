package site.xmpp.greenthumb.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The start-screen and Profile privacy texts must state what the release really does
 * (docs/privacy.html is the long form): optional name, crash/ANR reports and the
 * session event, no server logs about people, key stored only as a hash, retention.
 */
class PrivacyTextsTest {

    private fun strings(dir: String): Map<String, String> {
        val file = File("src/commonMain/composeResources/$dir/strings.xml")
        val re = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        return re.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val required = mapOf(
        "values" to listOf("name is optional", "crash", "ANR", "session event", "no logs about you", "only as a hash", "backup"),
        "values-ru" to listOf("Имя необязательно", "падениях", "ANR", "событие сеанса", "не ведёт журналов", "только в виде хеша", "резервных копий"),
    )

    @Test
    fun login_hint_and_profile_body_state_every_privacy_fact() {
        for ((dir, phrases) in required) {
            val s = strings(dir)
            for (key in listOf("privacy_loginHint", "privacy_aboutBody")) {
                val text = s.getValue(key)
                for (phrase in phrases) {
                    assertTrue(text.contains(phrase, ignoreCase = true), "$dir/$key misses \"$phrase\": $text")
                }
            }
        }
    }

    @Test
    fun no_string_tells_the_user_to_reinstall() {
        for (dir in required.keys) {
            val bad = strings(dir).filterValues { Regex("reinstall|переустанов", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            assertFalse(bad.isNotEmpty(), "$dir: $bad")
        }
    }
}
