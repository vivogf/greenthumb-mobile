package site.xmpp.greenthumb.core.platform

import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import site.xmpp.greenthumb.ui.components.PhotoPickerController

/**
 * VAL-APP-019: a gallery item the picker returns but the app cannot read (stale MediaStore row,
 * 0-byte file, revoked grant) becomes the photo-error result, never an uncaught exception.
 */
class PickedImageReadTest {

    private fun assertUnreadable(read: () -> ByteArray?) {
        val error = assertFailsWith<UnsupportedImageException> { readPickedImage(read) }
        assertEquals(UNSUPPORTED_IMAGE_MESSAGE, error.message)
    }

    @Test
    fun missingFile_isThePhotoError() = assertUnreadable {
        throw FileNotFoundException("open failed: ENOENT (No such file or directory)")
    }

    @Test
    fun ioFailure_isThePhotoError() = assertUnreadable { throw IOException("read failed") }

    @Test
    fun revokedGrant_isThePhotoError() = assertUnreadable { throw SecurityException("Permission Denial") }

    @Test
    fun zeroBytes_isThePhotoError() = assertUnreadable { ByteArray(0) }

    @Test
    fun noStream_isThePhotoError() = assertUnreadable { null }

    @Test
    fun readableBytes_arePicked() {
        val bytes = byteArrayOf(1, 2, 3)
        val result = assertIs<PickResult.Picked>(readPickedImage { bytes })
        assertContentEquals(bytes, result.bytes)
    }

    @Test
    fun controller_showsThePhotoError_forAnUnreadableItem() = runTest {
        var picked = 0
        val controller = PhotoPickerController(
            pick = { readPickedImage { throw FileNotFoundException("gone") } },
            crop = { bytes, _ -> bytes },
            onPicked = { picked++ },
        )
        controller.launch(backgroundScope, PickSource.Gallery)
        runCurrent()
        assertEquals(UNSUPPORTED_IMAGE_MESSAGE, controller.pickErrorMessage)
        assertEquals(null, controller.cropping)
        assertEquals(0, picked)
    }
}
