package site.xmpp.greenthumb.ui.theme

import site.xmpp.greenthumb.core.storage.ThemePreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Резолвер effective-схемы (Stage 6 п.3, порт RN contexts/ThemeContext.tsx):
 * auto (и null — «не задано», дефолт RN auto) следует системе; явные
 * light/dark перекрывают систему (VAL-THEME-001/002).
 */
class ThemeResolverTest {

    // auto: effective = system (обе стороны).

    @Test
    fun nullPreferenceFollowsSystemLight() {
        assertEquals(false, resolveDarkTheme(preference = null, systemDark = false))
    }

    @Test
    fun nullPreferenceFollowsSystemDark() {
        assertEquals(true, resolveDarkTheme(preference = null, systemDark = true))
    }

    @Test
    fun autoFollowsSystemLight() {
        assertEquals(false, resolveDarkTheme(preference = ThemePreference.Auto, systemDark = false))
    }

    @Test
    fun autoFollowsSystemDark() {
        assertEquals(true, resolveDarkTheme(preference = ThemePreference.Auto, systemDark = true))
    }

    // Явный выбор перекрывает систему (VAL-THEME-002).

    @Test
    fun lightOverridesDarkSystem() {
        assertFalse(resolveDarkTheme(preference = ThemePreference.Light, systemDark = true))
    }

    @Test
    fun darkOverridesLightSystem() {
        assertTrue(resolveDarkTheme(preference = ThemePreference.Dark, systemDark = false))
    }
}
