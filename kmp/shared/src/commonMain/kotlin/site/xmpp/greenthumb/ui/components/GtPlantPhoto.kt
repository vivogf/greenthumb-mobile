package site.xmpp.greenthumb.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

/**
 * Plant photo for dashboard tiles and the detail header. [photoUrl] is an
 * https URL, a legacy/offline `data:` URI or `""`. The leaf mark stays under
 * the image until it has loaded, so an empty URL, a slow load and an offline
 * miss all show the same static placeholder (never a spinner).
 */
@Composable
fun GtPlantPhoto(
    photoUrl: String,
    leafSize: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    var loaded by remember(photoUrl) { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (!loaded) {
            GtLeafMark(
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(leafSize),
            )
        }
        if (photoUrl.isNotBlank()) {
            AsyncImage(
                model = photoUrl,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onState = { loaded = it is AsyncImagePainter.State.Success },
            )
        }
    }
}
