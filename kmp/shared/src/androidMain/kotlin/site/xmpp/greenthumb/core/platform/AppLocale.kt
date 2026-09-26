package site.xmpp.greenthumb.core.platform

import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Детерминированный мост «настройка языка приложения → платформа»
 * (M6b, VAL-I18N-007): выбранный язык хранится в процессе отдельным
 * полем-множителем и применяется к доставленной платформой конфигурации
 * СИНХРОННО, вне Compose-прохода.
 *
 * Почему это не Compose-мутация (попытка M6 `Configuration.setLocale` +
 * `resources.updateConfiguration` внутри `provides` — удалена): доставка
 * конфигурации (uiMode-переключения на дашборде) доставляет НОВУЮ
 * Configuration с системной локалью ВНЕ Compose, а remembered-окружение
 * строк Compose Resources (DefaultComposeEnvironment, пин CMP 1.12.0:
 * remember(composeLocale…) от Locale.current → LocaleList.getDefault())
 * пересобирается от глобального LocaleList — любая свежая композиция
 * (ремаунт вкладки) могла прочитать системный EN при сохранённом ru
 * (интермиттент 2/5, evidence r2-anomaly-en-tabs-analysis.txt).
 *
 * Рабочая схема на Android (minSdk 24 → LocaleList доступен):
 *  - MainActivity.onCreate / onConfigurationChanged вызывают
 *    [applyAppLocaleToConfiguration] ДО dispatch в Compose: конфигурация
 *    каждой доставки снова несёт выбранный язык;
 *  - [provides] (LocalAppLocale.android) синхронно ставит глобальный
 *    Locale.setDefault — путь переключения языка в рантайме без доставки
 *    конфигурации;
 *  - любая композиция читает язык от этих детерминированных состояний.
 *
 * Настройка «системная» (null) — платформа не корректируется: системный
 * EN-фолбэк ресурсов values/ (дефолт RN).
 *
 * Активность одна (manifest-инвариант); поле — простое (как SessionManager:
 * доступ только с main-потоком UI). Публичность — граница модулей
 * (:androidApp не видит internal :shared); единственный внешний клиент —
 * MainActivity, остальные обращения к объекту только внутри shared/androidMain.
 */
public object AppLocale {
    @Volatile
    private var selected: Locale? = null

    /** Зафиксировать язык настройки для последующих доставок конфигурации. */
    internal fun remember(locale: Locale?) {
        selected = locale
    }

    /**
     * Синхронно вписать зафиксированный язык приложения в доставленную
     * конфигурацию: Configuration.setLocale + LocaleList.setDefault.
     * Возвращает true, если конфигурация изменилась (переприменение после
     * доставки — обычный случай; повторный вызов с уже вписанной локалью —
     * no-op, Configuration.setLocale перезаписывает тем же значением).
     */
    public fun applyToConfiguration(configuration: Configuration): Boolean {
        val locale = selected ?: return false
        val before = configuration.locale
        configuration.setLocale(locale)
        LocaleList.setDefault(LocaleList(locale))
        return before != locale
    }
}
