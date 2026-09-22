package site.xmpp.greenthumb.core.storage

/**
 * JVM-actual маркера обновления (desktop-харнесс): всегда false.
 *
 * Desktop-приложение Expo-сборкой никогда не было (та же причина, по которой
 * jvm-actual [LegacyHandoff] возвращает null): «установки поверх Expo» на
 * desktop не существует, экрану «ключ не найден» там взяться неоткуда.
 */
public actual fun isUpdateInstall(): Boolean = false
