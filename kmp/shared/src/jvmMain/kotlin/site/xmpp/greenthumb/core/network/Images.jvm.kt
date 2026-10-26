package site.xmpp.greenthumb.core.network

import coil3.PlatformContext
import okio.FileSystem
import okio.Path

internal actual fun imageDiskCacheDirectory(context: PlatformContext): Path =
    FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "greenthumb" / IMAGE_DISK_CACHE_DIR
