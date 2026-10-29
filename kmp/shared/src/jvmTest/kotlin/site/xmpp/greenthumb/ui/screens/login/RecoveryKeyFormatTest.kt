package site.xmpp.greenthumb.ui.screens.login

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Typed recovery keys: the Postgres `uuid` input rule, one canonical output. */
class RecoveryKeyFormatTest {

    private val canonical = "abcdef01-2345-6789-abcd-ef0123456789"

    @Test
    fun accepted_forms_become_the_canonical_key() {
        listOf(
            canonical,
            canonical.uppercase(),
            "  $canonical\n",
            "{$canonical}",
            "abcdef0123456789abcdef0123456789",
            "ABCD-EF01-2345-6789-ABCD-EF01-2345-6789",
            "{abcdef0123456789abcdef0123456789}",
        ).forEach { input -> assertEquals(canonical, canonicalRecoveryKey(input), "input: $input") }
    }

    @Test
    fun rejected_forms_give_null() {
        listOf(
            "",
            "typed-key",
            "abc-def01-2345-6789-abcd-ef0123456789",
            "abcdef01-2345-6789-abcd-ef012345678",
            "abcdef01-2345-6789-abcd-ef01234567890",
            "abcdef01-2345-6789-abcd-ef012345678g",
            "abcdef01--2345-6789-abcd-ef0123456789",
            "-abcdef01-2345-6789-abcd-ef0123456789",
            "abcdef01-2345-6789-abcd-ef0123456789-",
            "{$canonical",
            "$canonical}",
            "{{$canonical}}",
            "{ $canonical }",
        ).forEach { input -> assertNull(canonicalRecoveryKey(input), "input: $input") }
    }
}
