package site.xmpp.greenthumb.ui

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import org.junit.rules.ExternalResource

/**
 * For Compose tests that render photos outside App: a plain singleton loader for the test, dropped
 * afterwards, so Coil's lazily created default never leaks into the next test class in the JVM.
 */
@OptIn(DelicateCoilApi::class)
class PlainImageLoaderRule : ExternalResource() {
    override fun before() {
        SingletonImageLoader.setUnsafe { context -> ImageLoader.Builder(context).build() }
    }

    override fun after() {
        SingletonImageLoader.reset()
    }
}
