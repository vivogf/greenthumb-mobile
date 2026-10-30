package site.xmpp.greenthumb.ui.screens.species

import kotlinx.coroutines.test.runTest
import site.xmpp.greenthumb.core.network.SpeciesCandidateDto
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.paintedJpeg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M10 item 9b: «Узнать вид по фото» identifies the photo already chosen, without a second pick. */
class IdentifyChosenPhotoTest {

    @Test
    fun identifyPhotoUsesTheGivenPhotoWithoutPicking() = runTest {
        val sent = mutableListOf<String>()
        var picks = 0
        var resized = 0
        val photo = paintedJpeg(40, 30)
        val controller = IdentifyController(
            identifier = { uri, _ ->
                sent += uri
                listOf(SpeciesCandidateDto("Monstera deliciosa", listOf("Swiss cheese plant"), 0.8))
            },
            pick = {
                picks++
                PickResult.Cancelled
            },
            resize = { bytes ->
                resized++
                bytes
            },
        )
        controller.identifyPhoto(backgroundScope, photo, "ru") { null }!!.join()
        assertEquals(0, picks, "no second picker")
        assertEquals(1, resized, "the photo goes through resizeJpeg")
        assertEquals(1, sent.size, "exactly one identify request")
        assertTrue(sent.single().startsWith("data:image/jpeg;base64,"))
        assertTrue(controller.state is IdentifyState.Results, "state = ${controller.state}")
    }
}
