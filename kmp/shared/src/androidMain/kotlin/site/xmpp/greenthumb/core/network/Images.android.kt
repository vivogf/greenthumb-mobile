package site.xmpp.greenthumb.core.network

import coil3.PlatformContext
import okio.Path
import okio.Path.Companion.toOkioPath

internal actual fun imageDiskCacheDirectory(context: PlatformContext): Path =
    context.cacheDir.resolve(IMAGE_DISK_CACHE_DIR).toOkioPath()
