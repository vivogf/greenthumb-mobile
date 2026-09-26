package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue

/**
 * Локаль приложения для Compose Resources (Stage 6 п.4; architecture.md §3
 * «LocalAppLocale»): рантайм-переключение языка строк без рестарта — рецепт
 * документации CMP «Manage local resource environment» (раздел Locale).
 *
 * Ожидаемый потребитель — [AppEnvironment]: preference из AppSettings
 * (`greenthumb_language`, [site.xmpp.greenthumb.core.storage.AppLanguage]) или
 * null (= системная локаль; RN-дефолт: язык устройства, не из списка — en —
 * реализуется фолбэком ресурсов values/ = en).
 *
 * `provides(value)` не просто подставляет CompositionLocal: actual'ы
 * ПЕРЕЗАПИСЫВАЮТ платформенное состояние, из которого читают строки
 * `stringResource`/`pluralStringResource`:
 * - androidMain — `Configuration.setLocale` + `resources.updateConfiguration`
 *   (ресурсы читают конфигурацию контекста);
 * - jvmMain — `Locale.setDefault` (ресурсы desktop читают дефолтную локаль JVM).
 *
 * Смена языка — `key(customAppLocale)` в [AppEnvironment] вокруг контента:
 * дерево перекомпоновывается заново, включая плюральные формы.
 */
public expect object LocalAppLocale {
    public val current: String
        @Composable get

    @Composable
    public infix fun provides(value: String?): ProvidedValue<*>
}
