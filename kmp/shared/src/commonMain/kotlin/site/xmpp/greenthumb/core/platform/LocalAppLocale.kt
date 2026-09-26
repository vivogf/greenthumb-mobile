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
 * `stringResource`/`pluralStringResource`, И публикуют наблюдаемое значение:
 * - androidMain — `Configuration.setLocale` + `resources.updateConfiguration`
 *   (ресурсы читают конфигурацию контекста) + CompositionLocal (см. actual);
 * - jvmMain — `Locale.setDefault` (ресурсы desktop читают дефолтную локаль JVM)
 *   + CompositionLocal.
 *
 * Требование наблюдаемости (VAL-I18N-004/006): `current` обязан читаться из
 * состояния Compose, меняющегося в `provides`, — иначе [AppLocalizedContent]
 * не перекомпонуется, `key(locale)` не пересчитается, и подписи останутся на
 * старом языке до перемонтирования поддерева (мутации глобального
 * `Locale`/того же экземпляра `Configuration` Compose не наблюдает).
 *
 * Единственный `key(customAppLocale)` стоит в [AppLocalizedContent] вокруг
 * локализованного контента (исправление VAL-I18N-006): rememberNavController,
 * back stack и `session.startup()` живут вне ключа.
 */
public expect object LocalAppLocale {
    public val current: String
        @Composable get

    @Composable
    public infix fun provides(value: String?): ProvidedValue<*>
}
