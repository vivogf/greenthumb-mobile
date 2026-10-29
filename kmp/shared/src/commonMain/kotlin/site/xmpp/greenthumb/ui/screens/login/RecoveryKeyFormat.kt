package site.xmpp.greenthumb.ui.screens.login

/**
 * Same rule as Postgres `uuid` input: 32 hex digits, a hyphen only after a group of 4,
 * case-insensitive. One pair of braces around it is stripped before the match.
 */
private val RECOVERY_KEY_PATTERN = Regex("^[0-9a-f]{4}(-?[0-9a-f]{4}){7}$", RegexOption.IGNORE_CASE)

/**
 * A typed recovery key in the one form the app sends and stores (lowercase 8-4-4-4-12),
 * or null when the text is not a key. Null means nothing goes to the server.
 */
internal fun canonicalRecoveryKey(input: String): String? {
    var text = input.trim()
    if (text.length >= 2 && text.first() == '{' && text.last() == '}') {
        text = text.substring(1, text.length - 1)
    }
    if (!RECOVERY_KEY_PATTERN.matches(text)) return null
    val hex = text.replace("-", "").lowercase()
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}
