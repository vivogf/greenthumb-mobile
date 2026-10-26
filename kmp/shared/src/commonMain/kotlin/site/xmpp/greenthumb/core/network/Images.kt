package site.xmpp.greenthumb.core.network

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import okio.Path
import site.xmpp.greenthumb.core.platform.DataUriKeyer

/**
 * Name of the Coil disk cache directory inside the platform cache dir
 * (Android: `cacheDir/gt_image_cache`). Only downloaded https photos live
 * there; data URIs decode in memory and are never written to it.
 */
public const val IMAGE_DISK_CACHE_DIR: String = "gt_image_cache"

internal const val IMAGE_DISK_CACHE_MAX_BYTES: Long = 64L * 1024 * 1024

private const val IMAGE_MEMORY_CACHE_PERCENT: Double = 0.25

/** Platform cache directory for [IMAGE_DISK_CACHE_DIR]. */
internal expect fun imageDiskCacheDirectory(context: PlatformContext): Path

/**
 * The app's single Coil [ImageLoader]. Photos load through [apiClient]'s
 * Ktor client, so images use the same engine, timeout and cookies as the API
 * and no second network path exists (rule K1). Without a client (UI tests)
 * the loader still renders data URIs but has no network fetcher.
 */
public fun buildImageLoader(context: PlatformContext, apiClient: ApiClient?): ImageLoader =
    buildImageLoader(context, apiClient, imageDiskCacheDirectory(context))

internal fun buildImageLoader(
    context: PlatformContext,
    apiClient: ApiClient?,
    diskCacheDirectory: Path,
): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(DataUriKeyer())
            if (apiClient != null) {
                add(KtorNetworkFetcherFactory(httpClient = apiClient.client))
            }
        }
        .memoryCache {
            MemoryCache.Builder().maxSizePercent(context, IMAGE_MEMORY_CACHE_PERCENT).build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(diskCacheDirectory)
                .maxSizeBytes(IMAGE_DISK_CACHE_MAX_BYTES)
                .build()
        }
        .build()
