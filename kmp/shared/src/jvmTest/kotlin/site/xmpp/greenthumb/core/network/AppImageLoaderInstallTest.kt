package site.xmpp.greenthumb.core.network

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * The app's loader must win even when something rendered an image before App did (for example an
 * earlier Compose test in the same JVM): Coil's `setSafe` throws once its default loader exists.
 */
@OptIn(DelicateCoilApi::class)
class AppImageLoaderInstallTest {
    private val context = PlatformContext.INSTANCE

    @BeforeTest
    fun clean() = SingletonImageLoader.reset()

    @AfterTest
    fun tearDown() = SingletonImageLoader.reset()

    @Test
    fun replacesCoilDefaultLoaderCreatedBeforeApp() {
        SingletonImageLoader.get(context)
        val app = ImageLoader.Builder(context).build()

        installAppImageLoader { app }

        assertSame(app, SingletonImageLoader.get(context))
    }

    @Test
    fun installsBeforeFirstUse() {
        val app = ImageLoader.Builder(context).build()

        installAppImageLoader { app }

        assertSame(app, SingletonImageLoader.get(context))
    }

    @Test
    fun keepsTheAlreadyCreatedAppLoader() {
        val first = ImageLoader.Builder(context).build()
        installAppImageLoader { first }
        SingletonImageLoader.get(context)

        installAppImageLoader { ImageLoader.Builder(context).build() }

        assertSame(first, SingletonImageLoader.get(context), "a recreated App must keep the loader and its memory cache")
    }
}
