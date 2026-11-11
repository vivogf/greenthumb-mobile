package site.xmpp.greenthumb.core.platform

/** Desktop-actual: the harness has no screen reader, so every timer keeps its usual length. */
public actual object ScreenReader {
    public actual fun isOn(): Boolean = false
}
