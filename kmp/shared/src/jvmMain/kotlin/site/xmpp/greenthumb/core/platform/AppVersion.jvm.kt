package site.xmpp.greenthumb.core.platform

/**
 * Desktop-актуал: версия харнесса. Desktop не публикуется — константа
 * (паритет RN `Constants.expoConfig?.version ?? '1.0.0'`; источника версии
 * у jvm-процесса нет).
 */
public actual object AppVersion {
    public actual val name: String = "0.1.0"
}
