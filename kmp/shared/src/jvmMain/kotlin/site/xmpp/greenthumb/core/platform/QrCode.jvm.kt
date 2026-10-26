package site.xmpp.greenthumb.core.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

public actual fun encodeQr(text: String): QrMatrix {
    // Width/height 0: ZXing returns the natural grid, one cell per module.
    val bits = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QR_QUIET_ZONE_MODULES,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
    val size = bits.width
    return QrMatrix(size, BooleanArray(size * size) { i -> bits[i % size, i / size] })
}
