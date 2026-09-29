package site.xmpp.greenthumb.core.platform

/**
 * Desktop-actual (харнесс): тактильного отклика нет — no-op. Заглушка из
 * плана Stage 9 («haptics — no-op»); вызовы безопасны в UI-тестах.
 */
public actual fun platformHapticSink(): HapticSink = HapticSink { }
