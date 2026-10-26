package site.xmpp.greenthumb.catalog

import java.text.Normalizer

internal actual fun decomposeUnicode(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
