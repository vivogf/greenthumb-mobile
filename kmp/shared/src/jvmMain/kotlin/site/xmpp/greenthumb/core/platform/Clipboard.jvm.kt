package site.xmpp.greenthumb.core.platform

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Desktop-actual буфера обмена (харнесс): java.awt.datatransfer.
 *
 * AWT-подсистема лениво инициализируется при первом обращении к Toolkit;
 * Headless-исключение на харнессе не возникает (GUI-режим hotRun). Сбой
 * проглатывается runCatching: копирование — best-effort, RN тоже не
 * обрабатывал отказ.
 */
public actual object Clipboard {
    public actual fun copy(value: String) {
        runCatching {
            Toolkit.getDefaultToolkit()
                .systemClipboard
                .setContents(StringSelection(value), null)
        }
    }
}
