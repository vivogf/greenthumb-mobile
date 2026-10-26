package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlin.math.floor
import site.xmpp.greenthumb.core.platform.encodeQr

/**
 * QR code of [text], black on white in every theme (scanners need the
 * contrast). Modules snap to whole pixels so the code stays sharp.
 */
@Composable
internal fun GtQrCode(text: String, contentDescription: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { encodeQr(text) }
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Image
            },
    ) {
        drawRect(Color.White)
        val module = floor(size.minDimension / matrix.size).coerceAtLeast(1f)
        val origin = Offset(
            floor((size.width - module * matrix.size) / 2f),
            floor((size.height - module * matrix.size) / 2f),
        )
        for (y in 0 until matrix.size) {
            var x = 0
            while (x < matrix.size) {
                if (!matrix[x, y]) {
                    x++
                    continue
                }
                val start = x
                while (x < matrix.size && matrix[x, y]) x++
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(origin.x + start * module, origin.y + y * module),
                    size = Size((x - start) * module, module),
                )
            }
        }
    }
}
