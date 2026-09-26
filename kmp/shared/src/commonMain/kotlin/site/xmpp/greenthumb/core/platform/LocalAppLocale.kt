package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue

/**
 * Локаль приложения (Stage 6 п.4, architecture.md §3 «LocalAppLocale»):
 * рантайм-переключение языка строк без рестарта.
 *
 * Ожидаемый потребитель — [AppEnvironment]: preference из AppSettings
 * (`greenthumb_language`, [site.xmpp.greenthumb.core.storage.AppLanguage]) или
 * null (= системная локаль; RN-дефолт: язык устройства, не из списка — en —
 * реализуется фолбэком ресурсов values/ = en).
 *
 * Источник языка строк — ресурсы Compose через remembered ResourceEnvironment
 * (DefaultComposeEnvironment, пин CMP 1.12.0: remember(Locale.current, …);
 * на Android Locale.current читает LocaleList.getDefault()). M6b делает этот
 * источник ДЕТЕРМИНИРОВАННЫМ относительно настройки языка: androidMain
 * вписывает выбранный язык в Configuration/LocaleList каждой доставки
 * конфигурации синхронно (MainActivity/AppLocale — не из Compose-прохода;
 * класс дефекта r2-anomaly: remembered-окружение пересобирается от
 * сброшенного доставкой глобального LocaleList → системный EN при сохранённом
 * ru, интермиттент 2/5), jvmMain оставляет процессный Locale.setDefault как
 * источник desktop-строк, синхронно обновляемый в provides.
 *
 * Требование наблюдаемости (VAL-I18N-004/006): `current` обязан читаться из
 * состояния Compose, меняющегося в `provides`, — иначе [AppLocalizedContent]
 * не перекомпонуется, `key(locale)` не пересчитается, и подписи останутся на
 * старом языке до перемонтирования поддерева. Mutations платформенных
 * объектов в композиции (android: Configuration/Resources; jvm —
 * Locale.setDefault сохранён как источник desktop-строк) не служат
 * источником инвалидации — только CompositionLocal.
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
