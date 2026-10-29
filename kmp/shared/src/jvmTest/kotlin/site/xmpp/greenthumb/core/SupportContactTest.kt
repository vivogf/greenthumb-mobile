package site.xmpp.greenthumb.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One support address and one privacy page for the whole app (M10 D§1 items 2 and 11). */
class SupportContactTest {

    @Test
    fun email_is_proton() {
        assertEquals("greenthumbapp@proton.me", SUPPORT_EMAIL)
    }

    @Test
    fun privacy_url_points_at_the_published_page() {
        assertTrue(PRIVACY_POLICY_URL.startsWith("https://vivogf.github.io/greenthumb-mobile/"), PRIVACY_POLICY_URL)
        assertEquals("privacy", PRIVACY_POLICY_URL.substringAfterLast('/').substringBefore('.'))
        assertEquals("html", PRIVACY_POLICY_URL.substringAfterLast('.'))
    }

    /** Every auth error text that names an address names the support address. */
    @Test
    fun auth_error_texts_name_only_the_support_address() {
        val keys = listOf("login_errors_invalidRecoveryKey", "login_errors_networkError", "login_errors_serviceUnavailable")
        val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        for (dir in listOf("values", "values-ru")) {
            val xml = File("src/commonMain/composeResources/$dir/strings.xml").readText()
            for (key in keys) {
                val text = Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .find(xml)?.groupValues?.get(1) ?: error("$dir misses $key")
                for (match in email.findAll(text)) assertEquals(SUPPORT_EMAIL, match.value, "$dir/$key")
            }
        }
    }

    /** No other address is left in the app sources, resources or tests. */
    @Test
    fun no_old_address_in_kmp_sources() {
        // Built from parts so that a repo-wide grep for the old addresses finds no line here.
        val old = listOf("pass" + "mail", "taunt" + "861", "greenthumb@" + "example", "@" + "example.com")
        val hits = File("src").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml") }
            .filter { file -> file.readText().let { text -> old.any { it in text } } }
            .map { it.path }
            .toList()
        assertEquals(emptyList(), hits)
    }
}
