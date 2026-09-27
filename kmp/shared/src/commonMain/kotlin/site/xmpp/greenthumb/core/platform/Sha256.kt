package site.xmpp.greenthumb.core.platform

/**
 * SHA-256 от строки в нижнем hex (32 байта → 64 символа).
 *
 * Нужен ключу кэша Coil [DataUriKeyer]: data-URI фото в сотни килобайт нельзя
 * держать ключом кэша (Stage 8 п.4). Платформенный примитив через
 * expect/actual: androidMain/jvmMain — `java.security.MessageDigest`
 * (нативный SHA-256, без новых зависимостей в пинах миссии).
 */
expect fun sha256Hex(input: String): String
