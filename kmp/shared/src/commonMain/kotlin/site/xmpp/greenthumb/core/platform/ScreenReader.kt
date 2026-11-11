package site.xmpp.greenthumb.core.platform

/**
 * Accessibility state the UI adapts to. Today only the screen reader, because a message that closes by itself
 * gives a screen-reader user less time than a glance gives everyone else (the «Отменить» bar of a care mark).
 */
public expect object ScreenReader {
    /**
     * True while a screen reader with touch exploration is on (Android: an enabled accessibility service plus
     * touch exploration, i.e. TalkBack in its usual mode). False on the desktop harness.
     */
    public fun isOn(): Boolean
}
