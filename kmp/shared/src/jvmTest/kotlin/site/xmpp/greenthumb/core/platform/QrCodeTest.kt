package site.xmpp.greenthumb.core.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VAL-CRED-014: the production encoder ([encodeQr]) output decodes back with
 * ZXing's own reader to exactly the recovery key: no prefix, URL or extra fields.
 */
class QrCodeTest {

    private fun decode(matrix: QrMatrix, scale: Int = 4): String {
        val side = matrix.size * scale
        val pixels = IntArray(side * side)
        for (y in 0 until side) for (x in 0 until side) {
            pixels[y * side + x] = if (matrix[x / scale, y / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        val hints = mapOf(DecodeHintType.PURE_BARCODE to true, DecodeHintType.CHARACTER_SET to "UTF-8")
        val result = QRCodeReader().decode(bitmap, hints)
        assertEquals(BarcodeFormat.QR_CODE, result.barcodeFormat)
        return result.text
    }

    @Test
    fun recoveryKeys_roundTripExactly() {
        val keys = List(3) { UUID.randomUUID().toString() } + "00000000-0000-4000-8000-000000000000"
        for (key in keys) {
            assertEquals(key, decode(encodeQr(key)), "decoded payload equals the key")
        }
    }

    @Test
    fun matrix_isSquare_withQuietZone() {
        val matrix = encodeQr(UUID.randomUUID().toString())
        assertTrue(matrix.size >= 21 + 2 * QR_QUIET_ZONE_MODULES, "version ≥ 1 plus quiet zone")
        for (i in 0 until matrix.size) {
            for (q in 0 until QR_QUIET_ZONE_MODULES) {
                assertTrue(!matrix[i, q] && !matrix[q, i], "quiet zone stays light")
                assertTrue(!matrix[i, matrix.size - 1 - q] && !matrix[matrix.size - 1 - q, i], "quiet zone stays light")
            }
        }
    }
}
