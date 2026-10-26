package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable

/** Light modules on each side of the code (ISO/IEC 18004 quiet zone). */
public const val QR_QUIET_ZONE_MODULES: Int = 4

/** Square QR module grid, quiet zone included; `true` = dark module. */
public class QrMatrix(public val size: Int, private val dark: BooleanArray) {
    init {
        require(size > 0 && dark.size == size * size)
    }

    public operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

/**
 * Encodes [text] as a QR code, one cell per module. ZXing is JVM-only, so the
 * encoder lives in the androidMain/jvmMain actuals. The payload is [text]
 * as-is: no scheme, prefix or extra fields.
 */
public expect fun encodeQr(text: String): QrMatrix

/**
 * Marks the app window secure while this composable is in the composition:
 * no screenshots, screen recording or recents thumbnail. No-op on desktop.
 */
@Composable
public expect fun SecureWindow()
