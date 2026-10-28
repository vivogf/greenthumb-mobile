package site.xmpp.greenthumb.ui.screens.login

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.platform.VaultGetResult
import site.xmpp.greenthumb.core.platform.VaultSaveResult
import site.xmpp.greenthumb.core.platform.platformRecoveryKeyVault
import site.xmpp.greenthumb.core.platform.recoveryKeyCredentialId
import site.xmpp.greenthumb.core.storage.SessionState

/**
 * Recovery key in the device password manager (architecture §5.3): the outcome of
 * "Sign in with saved key" for every vault result, and the jvm actual.
 */
class SavedKeySignInTest {

    @Test
    fun credential_id_is_the_stable_english_label() {
        assertEquals("GreenThumb #4242", recoveryKeyCredentialId("4242"))
    }

    @Test
    fun jvm_vault_is_unsupported_for_save_and_get() = runBlocking {
        val vault = platformRecoveryKeyVault()
        assertFalse(vault.isSupported)
        assertEquals(VaultSaveResult.Unsupported, vault.save("1", VAULT_TEST_KEY))
        assertEquals(VaultGetResult.Unsupported, vault.get())
    }

    @Test
    fun found_result_never_prints_the_key() {
        val text = VaultGetResult.Found(VAULT_TEST_KEY).toString()
        assertFalse(text.contains(VAULT_TEST_KEY), text)
    }

    @Test
    fun found_key_goes_through_the_normal_login_recovery_path() = runBlocking {
        val calls = mutableListOf<RecordedCall>()
        val session = vaultSession(calls, loginRecoveryResponder())
        val vault = FakeVault(getResult = VaultGetResult.Found(VAULT_TEST_KEY))

        val outcome = signInWithSavedKey(vault, session)

        assertEquals(SavedKeyOutcome.SignedIn, outcome)
        assertEquals(1, calls.size)
        assertEquals("POST", calls[0].method)
        assertEquals("/api/auth/login-recovery", calls[0].path)
        assertTrue(calls[0].body.contains(VAULT_TEST_KEY))
        val state = assertIs<SessionState.SignedIn>(session.state.value)
        assertEquals(VAULT_TEST_USER_ID, state.user.id)
    }

    @Test
    fun cancelled_picker_makes_no_request() = runBlocking {
        val calls = mutableListOf<RecordedCall>()
        val session = vaultSession(calls, loginRecoveryResponder())
        val outcome = signInWithSavedKey(FakeVault(getResult = VaultGetResult.Cancelled), session)
        assertEquals(SavedKeyOutcome.Cancelled, outcome)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun no_credential_makes_no_request() = runBlocking {
        val calls = mutableListOf<RecordedCall>()
        val session = vaultSession(calls, loginRecoveryResponder())
        val outcome = signInWithSavedKey(FakeVault(getResult = VaultGetResult.NoCredential), session)
        assertEquals(SavedKeyOutcome.NoCredential, outcome)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun unsupported_vault_is_reported_without_a_picker_call() = runBlocking {
        val calls = mutableListOf<RecordedCall>()
        val session = vaultSession(calls, loginRecoveryResponder())
        val vault = FakeVault(isSupported = false, getResult = VaultGetResult.Found(VAULT_TEST_KEY))
        assertEquals(SavedKeyOutcome.Unsupported, signInWithSavedKey(vault, session))
        assertEquals(0, vault.getCalls)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun runtime_unsupported_and_failure_are_distinct() = runBlocking {
        val calls = mutableListOf<RecordedCall>()
        val session = vaultSession(calls, loginRecoveryResponder())
        assertEquals(
            SavedKeyOutcome.Unsupported,
            signInWithSavedKey(FakeVault(getResult = VaultGetResult.Unsupported), session),
        )
        assertEquals(
            SavedKeyOutcome.Failed,
            signInWithSavedKey(FakeVault(getResult = VaultGetResult.Failed), session),
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun stale_saved_key_401_is_out_of_date() = runBlocking {
        val calls = mutableListOf<RecordedCall>()
        val session = vaultSession(calls, loginRecoveryResponder(acceptedKey = VAULT_TEST_NEW_KEY))
        val outcome = signInWithSavedKey(FakeVault(getResult = VaultGetResult.Found(VAULT_TEST_KEY)), session)
        assertEquals(SavedKeyOutcome.StaleKey, outcome)
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun malformed_saved_key_400_is_out_of_date_too() = runBlocking {
        val calls = mutableListOf<RecordedCall>()
        val session = vaultSession(calls) {
            respond(
                """{"error":"invalid input syntax for type uuid"}""",
                HttpStatusCode.BadRequest,
                headersOf("Content-Type", "application/json"),
            )
        }
        val outcome = signInWithSavedKey(FakeVault(getResult = VaultGetResult.Found("not-a-uuid")), session)
        assertEquals(SavedKeyOutcome.StaleKey, outcome)
    }

    @Test
    fun server_failure_is_not_reported_as_a_stale_key() = runBlocking {
        val calls = mutableListOf<RecordedCall>()
        val session = vaultSession(calls) {
            respond("{}", HttpStatusCode.ServiceUnavailable, headersOf("Content-Type", "application/json"))
        }
        val outcome = signInWithSavedKey(FakeVault(getResult = VaultGetResult.Found(VAULT_TEST_KEY)), session)
        assertIs<SavedKeyOutcome.AuthFailed>(outcome)
        Unit
    }
}
