package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.LocalRecoveryKeyVault
import site.xmpp.greenthumb.core.platform.PushTokens
import site.xmpp.greenthumb.core.platform.RecoveryKeyVault
import site.xmpp.greenthumb.core.platform.VaultGetResult
import site.xmpp.greenthumb.core.platform.VaultSaveResult
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.ui.screens.profile.ProfileScreen
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Vault outcomes on every surface (VAL-CRED-002): show-key, login (existing
 * key), Key not found and Profile, all with a scripted [FakeVault].
 */
@OptIn(ExperimentalTestApi::class)
class RecoveryKeyVaultUiTest {

    private var originalLocale: Locale? = null

    @BeforeTest
    fun rememberLocale() {
        originalLocale = Locale.getDefault()
    }

    @AfterTest
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
    }

    private fun ComposeUiTest.show(vault: RecoveryKeyVault, content: @Composable () -> Unit) {
        setContent {
            AppEnvironment(customAppLocale = "en") {
                GreenThumbTheme(darkTheme = false) {
                    CompositionLocalProvider(LocalRecoveryKeyVault provides vault) { content() }
                }
            }
        }
    }

    private fun ComposeUiTest.assertNoErrorText() {
        onNodeWithText(MSG_FAILED_SAVE).assertDoesNotExist()
        onNodeWithText(MSG_UNAVAILABLE_SAVE).assertDoesNotExist()
        onNodeWithText("Error").assertDoesNotExist()
    }

    private fun ComposeUiTest.openLoginKeyMode(session: SessionManager, vault: RecoveryKeyVault) {
        show(vault) { LoginScreen(session = session, initialMode = LoginMode.Login) }
        waitUntilAtLeastOneExists(hasText("Welcome Back"), TIMEOUT)
    }

    private fun ComposeUiTest.assertManualFieldEnabled() {
        waitUntilAtLeastOneExists(hasSetTextAction() and isEnabled(), TIMEOUT)
    }

    // ---------------------------------------------------------------- show-key

    @Test
    fun show_key_save_cancelled_is_silent_and_gate_still_required() = runDesktopComposeUiTest {
        val vault = FakeVault(saveResult = VaultSaveResult.Cancelled)
        var continued = false
        show(vault) {
            ShowKeySurface(recoveryKey = VAULT_TEST_KEY, userId = VAULT_TEST_USER_ID, onContinue = { continued = true })
        }
        waitUntilAtLeastOneExists(hasText(BTN_SAVE), TIMEOUT)
        onNodeWithText(BTN_SAVE).performClick()
        waitForIdle()

        assertEquals(listOf("GreenThumb #$VAULT_TEST_USER_ID"), vault.savedIds)
        assertEquals(listOf(VAULT_TEST_KEY), vault.savedKeys)
        assertNoErrorText()
        onNodeWithText(MSG_SAVED).assertDoesNotExist()
        onNodeWithText(GATE).assertExists()
        assertTrue(!continued, "cancel must not leave the show-key gate")
    }

    @Test
    fun show_key_save_success_shows_confirmation_and_keeps_gate() = runDesktopComposeUiTest {
        val vault = FakeVault(saveResult = VaultSaveResult.Saved)
        var continued = false
        show(vault) {
            ShowKeySurface(recoveryKey = VAULT_TEST_KEY, userId = VAULT_TEST_USER_ID, onContinue = { continued = true })
        }
        waitUntilAtLeastOneExists(hasText(BTN_SAVE), TIMEOUT)
        onNodeWithText(BTN_SAVE).performClick()
        waitUntilAtLeastOneExists(hasText(MSG_SAVED), TIMEOUT)
        onNodeWithText(GATE).assertExists()
        assertTrue(!continued, "save success must not auto-navigate")
        onNodeWithText(GATE).performClick()
        waitForIdle()
        assertTrue(continued)
    }

    @Test
    fun show_key_without_provider_explains_and_keeps_copy_and_gate() = runDesktopComposeUiTest {
        val vault = FakeVault(isSupported = false)
        show(vault) { ShowKeySurface(recoveryKey = VAULT_TEST_KEY, userId = VAULT_TEST_USER_ID, onContinue = {}) }
        waitUntilAtLeastOneExists(hasText(MSG_UNAVAILABLE_SAVE), TIMEOUT)
        onNodeWithText(BTN_SAVE).assertDoesNotExist()
        onNodeWithText(GATE).assertExists()
        assertTrue(vault.savedIds.isEmpty())
    }

    @Test
    fun show_key_runtime_unsupported_replaces_button_with_explanation() = runDesktopComposeUiTest {
        val vault = FakeVault(saveResult = VaultSaveResult.Unsupported)
        show(vault) { ShowKeySurface(recoveryKey = VAULT_TEST_KEY, userId = VAULT_TEST_USER_ID, onContinue = {}) }
        waitUntilAtLeastOneExists(hasText(BTN_SAVE), TIMEOUT)
        onNodeWithText(BTN_SAVE).performClick()
        waitUntilAtLeastOneExists(hasText(MSG_UNAVAILABLE_SAVE), TIMEOUT)
        onNodeWithText(BTN_SAVE).assertDoesNotExist()
        onNodeWithText(GATE).assertExists()
    }

    // ---------------------------------------------------------------- login

    @Test
    fun login_picker_cancelled_stays_on_login_without_error() = runDesktopComposeUiTest {
        val calls = mutableListOf<RecordedCall>()
        val vault = FakeVault(getResult = VaultGetResult.Cancelled)
        openLoginKeyMode(vaultSession(calls, loginRecoveryResponder()), vault)
        onNodeWithText(BTN_SIGN_IN_SAVED).performClick()
        waitForIdle()
        assertEquals(1, vault.getCalls)
        onNodeWithTag(TAG_MESSAGE, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Welcome Back").assertExists()
        assertManualFieldEnabled()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun login_no_credential_shows_friendly_message_and_manual_field() = runDesktopComposeUiTest {
        val calls = mutableListOf<RecordedCall>()
        openLoginKeyMode(vaultSession(calls, loginRecoveryResponder()), FakeVault(getResult = VaultGetResult.NoCredential))
        onNodeWithText(BTN_SIGN_IN_SAVED).performClick()
        waitUntilAtLeastOneExists(hasText(MSG_NO_CREDENTIAL), TIMEOUT)
        assertManualFieldEnabled()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun login_without_provider_hides_button_and_keeps_manual_entry() = runDesktopComposeUiTest {
        val calls = mutableListOf<RecordedCall>()
        openLoginKeyMode(vaultSession(calls, loginRecoveryResponder()), FakeVault(isSupported = false))
        onNodeWithText(BTN_SIGN_IN_SAVED).assertDoesNotExist()
        assertManualFieldEnabled()
    }

    @Test
    fun login_runtime_unsupported_explains_and_hides_button() = runDesktopComposeUiTest {
        val calls = mutableListOf<RecordedCall>()
        openLoginKeyMode(vaultSession(calls, loginRecoveryResponder()), FakeVault(getResult = VaultGetResult.Unsupported))
        onNodeWithText(BTN_SIGN_IN_SAVED).performClick()
        waitUntilAtLeastOneExists(hasText(MSG_UNAVAILABLE_RESTORE), TIMEOUT)
        onNodeWithText(BTN_SIGN_IN_SAVED).assertDoesNotExist()
        assertManualFieldEnabled()
    }

    @Test
    fun login_found_key_signs_in_through_login_recovery() = runDesktopComposeUiTest {
        val calls = mutableListOf<RecordedCall>()
        val session = vaultSession(calls, loginRecoveryResponder())
        openLoginKeyMode(session, FakeVault(getResult = VaultGetResult.Found(VAULT_TEST_KEY)))
        onNodeWithText(BTN_SIGN_IN_SAVED).performClick()
        waitUntil(timeoutMillis = TIMEOUT) { session.state.value is SessionState.SignedIn }
        assertEquals(listOf("/api/auth/login-recovery"), calls.map { it.path })
        assertTrue(calls[0].body.contains(VAULT_TEST_KEY))
        assertEquals(VAULT_TEST_USER_ID, (session.state.value as SessionState.SignedIn).user.id)
    }

    @Test
    fun login_stale_key_shows_out_of_date_message_and_manual_field() = runDesktopComposeUiTest {
        val calls = mutableListOf<RecordedCall>()
        val session = vaultSession(calls, loginRecoveryResponder(acceptedKey = VAULT_TEST_NEW_KEY))
        openLoginKeyMode(session, FakeVault(getResult = VaultGetResult.Found(VAULT_TEST_KEY)))
        onNodeWithText(BTN_SIGN_IN_SAVED).performClick()
        waitUntilAtLeastOneExists(hasText(MSG_STALE), TIMEOUT)
        assertManualFieldEnabled()
        assertEquals(1, calls.size, "no sign-in loop")
        assertEquals(SessionState.SignedOut, session.state.value)
    }

    @Test
    fun login_initial_notice_is_shown_on_entry() = runDesktopComposeUiTest {
        val calls = mutableListOf<RecordedCall>()
        show(FakeVault()) {
            LoginScreen(
                session = vaultSession(calls, loginRecoveryResponder()),
                initialMode = LoginMode.Login,
                initialNotice = SavedKeyOutcome.StaleKey,
            )
        }
        waitUntilAtLeastOneExists(hasText(MSG_STALE), TIMEOUT)
        assertManualFieldEnabled()
    }

    // ---------------------------------------------------------------- key not found

    @Test
    fun key_not_found_offers_saved_key_and_shows_its_message() = runDesktopComposeUiTest {
        var restores = 0
        show(FakeVault()) {
            KeyNotFoundSurface(
                onEnterLogin = {},
                onEnterCreate = {},
                onSignInWithSavedKey = { restores++ },
                notice = SavedKeyOutcome.NoCredential,
            )
        }
        waitUntilAtLeastOneExists(hasText(BTN_SIGN_IN_SAVED), TIMEOUT)
        waitUntilAtLeastOneExists(hasText(MSG_NO_CREDENTIAL), TIMEOUT)
        onNodeWithText("Enter recovery key").assertExists()
        onNodeWithText(BTN_SIGN_IN_SAVED).performClick()
        waitForIdle()
        assertEquals(1, restores)
        onNodeWithText("reinstall", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun key_not_found_without_provider_hides_saved_key_button() = runDesktopComposeUiTest {
        show(FakeVault(isSupported = false)) {
            KeyNotFoundSurface(onEnterLogin = {}, onEnterCreate = {}, onSignInWithSavedKey = {}, notice = null)
        }
        waitUntilAtLeastOneExists(hasText("Enter recovery key"), TIMEOUT)
        onNodeWithText(BTN_SIGN_IN_SAVED).assertDoesNotExist()
    }

    // ---------------------------------------------------------------- profile

    private fun signedInSession(calls: MutableList<RecordedCall>): SessionManager {
        val session = vaultSession(calls, loginRecoveryResponder())
        runBlocking { session.signInWithRecoveryKey(VAULT_TEST_KEY) }
        calls.clear()
        return session
    }

    @Test
    fun profile_save_uses_user_id_label_and_confirms() = runDesktopComposeUiTest {
        val calls = mutableListOf<RecordedCall>()
        val session = signedInSession(calls)
        val vault = FakeVault(saveResult = VaultSaveResult.Saved)
        show(vault) {
            ProfileScreen(session = session, settings = VaultFakeSettings(), push = PushTokens(), pushLanguage = { "en" })
        }
        waitUntilAtLeastOneExists(hasTestTag(TAG_SAVE), TIMEOUT)
        onNodeWithTag(TAG_SAVE).performClick()
        waitUntilAtLeastOneExists(hasText(MSG_SAVED), TIMEOUT)
        assertEquals(listOf("GreenThumb #$VAULT_TEST_USER_ID"), vault.savedIds)
        assertEquals(listOf(VAULT_TEST_KEY), vault.savedKeys)
    }

    @Test
    fun profile_save_cancelled_is_silent_and_session_stays() = runDesktopComposeUiTest {
        val calls = mutableListOf<RecordedCall>()
        val session = signedInSession(calls)
        val vault = FakeVault(saveResult = VaultSaveResult.Cancelled)
        show(vault) {
            ProfileScreen(session = session, settings = VaultFakeSettings(), push = PushTokens(), pushLanguage = { "en" })
        }
        waitUntilAtLeastOneExists(hasTestTag(TAG_SAVE), TIMEOUT)
        onNodeWithTag(TAG_SAVE).performClick()
        waitForIdle()
        assertEquals(1, vault.savedIds.size)
        assertNoErrorText()
        onNodeWithText(MSG_SAVED).assertDoesNotExist()
        assertTrue(session.state.value is SessionState.SignedIn)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun profile_after_regeneration_offers_new_key_save_under_same_id() = runDesktopComposeUiTest {
        val calls = mutableListOf<RecordedCall>()
        val session = signedInSession(calls)
        val vault = FakeVault(saveResult = VaultSaveResult.Saved)
        show(vault) {
            ProfileScreen(session = session, settings = VaultFakeSettings(), push = PushTokens(), pushLanguage = { "en" })
        }
        waitUntilAtLeastOneExists(hasText("Generate New Key"), TIMEOUT)
        onNodeWithText("Generate New Key").performClick()
        waitForIdle()
        // Confirm dialog: title and destructive button both repeat the row label.
        waitUntil(timeoutMillis = TIMEOUT) { onAllNodes(hasText("Generate New Key")).fetchSemanticsNodes().size >= 3 }
        onAllNodesWithTextLast("Generate New Key").performClick()
        waitUntil(timeoutMillis = TIMEOUT) {
            (session.state.value as? SessionState.SignedIn)?.user?.recoveryKey == VAULT_TEST_NEW_KEY
        }
        waitUntilAtLeastOneExists(hasText("Key regenerated"), TIMEOUT)
        onNodeWithText("OK").performClick()
        waitForIdle()
        // The offer to update the saved entry replaces the plain save label.
        waitUntilAtLeastOneExists(hasText(BTN_SAVE_NEW), TIMEOUT)
        onNodeWithText(BTN_SAVE).assertDoesNotExist()
        onNodeWithText(BTN_SAVE_NEW).performClick()
        waitUntilAtLeastOneExists(hasText(MSG_SAVED), TIMEOUT)
        assertEquals(listOf("GreenThumb #$VAULT_TEST_USER_ID"), vault.savedIds)
        assertEquals(listOf(VAULT_TEST_NEW_KEY), vault.savedKeys)
    }

    private fun ComposeUiTest.onAllNodesWithTextLast(text: String) =
        onAllNodes(hasText(text)).let { nodes ->
            nodes[nodes.fetchSemanticsNodes().size - 1]
        }

    private companion object {
        const val TIMEOUT: Long = 10_000L
        const val TAG_SAVE = "vault_save"
        const val TAG_MESSAGE = "vault_message"
        const val GATE = "I've Saved My Key — Continue"
        const val BTN_SAVE = "Save key to password manager"
        const val BTN_SAVE_NEW = "Save new key to password manager"
        const val BTN_SIGN_IN_SAVED = "Sign in with saved key"
        const val MSG_SAVED = "Key saved to your password manager."
        const val MSG_FAILED_SAVE = "Couldn't save the key to a password manager. Copy it and keep it in a safe place."
        const val MSG_UNAVAILABLE_SAVE =
            "No password manager is available on this device. Copy the key and keep it in a safe place."
        const val MSG_NO_CREDENTIAL = "No saved key found on this device. Enter your recovery key manually."
        const val MSG_UNAVAILABLE_RESTORE =
            "No password manager is available on this device. Enter your recovery key manually."
        const val MSG_STALE = "This saved key is out of date. Enter your current recovery key manually."
    }
}
