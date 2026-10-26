package site.xmpp.greenthumb.core.network

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.awt.image.BufferedImage
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Coil goes to the network only through the ApiClient Ktor client (rule K1),
 * keeps https photos in its disk cache for offline starts and still renders
 * data: URIs without any request.
 */
class ImageLoaderTest {

    private val context = PlatformContext.INSTANCE
    private val cacheDir = Files.createTempDirectory("gt-image-cache-test")
    private val photoUrl = "https://greenthumb.xmpp.site/photos/0f8fad5b-d9cb-469f-a165-70867728950e.jpg"
    private val png = pngBytes()

    private var online = true
    private val requestedUrls = mutableListOf<String>()
    private val engine = MockEngine { request ->
        requestedUrls += request.url.toString()
        if (!online) throw IOException("airplane mode")
        respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
    }
    private val api = ApiClient(engine)

    @AfterTest
    fun cleanUp() {
        api.close()
        cacheDir.toFile().deleteRecursively()
    }

    private fun loader(client: ApiClient? = api): ImageLoader =
        buildImageLoader(context, client, cacheDir.toOkioPath())

    private fun request(data: String, readMemory: Boolean = true) =
        ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(if (readMemory) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()

    @Test
    fun httpsPhotoIsFetchedThroughTheApiClientEngine(): Unit = runBlocking {
        val result = loader().execute(request(photoUrl))

        assertIs<SuccessResult>(result, "https photo must load: $result")
        assertEquals(listOf(photoUrl), requestedUrls, "exactly one request, through the shared Ktor engine")
    }

    @Test
    fun diskCacheServesThePhotoWhenOffline(): Unit = runBlocking {
        val images = loader()
        assertIs<SuccessResult>(images.execute(request(photoUrl)))
        online = false
        requestedUrls.clear()

        val offline = images.execute(request(photoUrl, readMemory = false))

        assertIs<SuccessResult>(offline, "cached photo must load offline: $offline")
        assertTrue(requestedUrls.isEmpty(), "disk cache hit must not touch the network: $requestedUrls")
        assertTrue(cacheDir.toFile().walk().any { it.isFile && it.length() > 0 }, "disk cache files are written")
    }

    @Test
    fun uncachedPhotoOfflineEndsInErrorNotHang(): Unit = runBlocking {
        online = false

        val result = loader().execute(request(photoUrl))

        assertIs<ErrorResult>(result)
    }

    @Test
    fun dataUriRendersWithoutNetworkAndUsesShortKey(): Unit = runBlocking {
        val dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png)
        val images = loader()

        val result = images.execute(request(dataUri))

        assertIs<SuccessResult>(result, "data URI must render: $result")
        assertTrue(requestedUrls.isEmpty(), "data URI must not hit the network")
        val options = Options(context)
        val key = images.components.key(images.components.map(dataUri, options), options)
        assertEquals(64, key?.length, "data URIs keep the SHA-256 cache key")
    }

    @Test
    fun loaderWithoutApiClientHasNoNetworkPath(): Unit = runBlocking {
        val result = loader(client = null).execute(request(photoUrl))

        assertIs<ErrorResult>(result)
        assertTrue(requestedUrls.isEmpty())
    }

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 4) for (y in 0 until 4) image.setRGB(x, y, 0xFF00FF)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }
}
