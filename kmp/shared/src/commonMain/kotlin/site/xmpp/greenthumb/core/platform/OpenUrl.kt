package site.xmpp.greenthumb.core.platform

/**
 * Открытие внешней ссылки (architecture.md §3 «OpenUrl», первое потребление
 * фичей screen-login M7: support-email на choose-режиме, RN
 * `Linking.openURL('mailto:…')` в `app/(auth)/login.tsx:398`).
 *
 * Неудача молча проглатывается вызывающим? НЕТ — вызывающий решает: RN
 * не обрабатывал обещание (fire-and-forget); KMP — синхронный best-effort
 * без исключений наверх, результат не влияет на навигацию. mailto на
 * desktop-харнессе обычно не зарегистрирован — no-op допустим.
 *
 * androidMain — Intent ACTION_VIEW; jvmMain — Desktop.browse (харнесс).
 */
public expect object OpenUrl {
    /** Открыть [url] системным обработчиком (mailto:/https:) — best-effort. */
    public fun open(url: String)
}
