package site.xmpp.greenthumb.ui.screens.login

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** System Back on the login screen: inner modes return to Choose, Choose leaves Back to the system. */
class LoginBackTest {

    @Test
    fun create_mode_returns_to_choose() {
        assertEquals(LoginMode.Choose, loginBackTarget(LoginMode.Create))
    }

    @Test
    fun login_mode_returns_to_choose() {
        assertEquals(LoginMode.Choose, loginBackTarget(LoginMode.Login))
    }

    @Test
    fun choose_mode_keeps_default_back() {
        assertNull(loginBackTarget(LoginMode.Choose))
    }
}
