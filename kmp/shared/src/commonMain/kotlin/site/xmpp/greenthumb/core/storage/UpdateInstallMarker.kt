package site.xmpp.greenthumb.core.storage

/**
 * Факт «установка — обновление поверх предыдущей установки» (Stage 3 п.6,
 * architecture.md §6: «установка является обновлением (Android, lastUpdateTime ≠
 * firstInstallTime)»).
 *
 * Формула из плана: `PackageInfo.lastUpdateTime != firstInstallTime`. На свежей
 * установке обе метки совпадают (Google Play ставит их одним пакетом) — «обновление»
 * false. Обновление поверх существующей установки различает их.
 *
 * Платформенные actual'ы:
 * - androidMain: `PackageManager.getPackageInfo(..., GET_UPDATES)` против
 *   [ANDROID_PACKAGE] (com.greenthumbplantcare); любой сбой (нет пакета, нет
 *   меток) → false (ведёт себя как «не обновление» — обычный экран входа);
 * - jvmMain: всегда false — desktop-харнесс Expo-сборкой никогда не был,
 *   экрану «ключ не найден» там взяться неоткуда (same reason LegacyHandoff
 *   jvm-actual возвращает null).
 *
 * iOS — вне скоупа миссии (architecture.md §0): ios-actual по той же причине
 * вернул бы false.
 *
 * Вызывается один раз на старте ([SessionGraph]) до крутящегося
 * [SessionManager.startup]; результат — конструкторный факт, не реактивное
 * состояние (установка не меняет свойство «обновление» в процессе работы).
 */
public expect fun isUpdateInstall(): Boolean
