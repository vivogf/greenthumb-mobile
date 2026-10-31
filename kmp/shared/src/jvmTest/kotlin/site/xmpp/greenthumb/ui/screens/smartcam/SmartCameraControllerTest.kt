package site.xmpp.greenthumb.ui.screens.smartcam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.DiagnosisCauseDto
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.network.SpeciesCandidateDto
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisError
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisLogic
import site.xmpp.greenthumb.ui.screens.species.ScoreLevel
import site.xmpp.greenthumb.ui.screens.species.SpeciesIdentifier

/**
 * VAL-CAM11-003/004/006/007/008/009/011/016 without UI: the in-app camera opens on the viewfinder, a shot or a
 * gallery pick only shows the photo, and only «Узнать вид» / «Проверить растение» sends it, once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartCameraControllerTest {

    private class Fakes {
        val identifyCalls = mutableListOf<Pair<String, String>>()
        val diagnoseCalls = mutableListOf<Triple<String, String?, String>>()
        val picks = mutableListOf<PickSource>()
        val shots = mutableListOf<Boolean>()
        val remembered = mutableListOf<SmartCameraMode>()
        var pickResult: PickResult = PickResult.Picked(byteArrayOf(1, 2, 3))
        var shotResult: PickResult = PickResult.Picked(byteArrayOf(4, 5, 6), capturePath = "/cache/gt-pick/gt-diag-1.jpg")
        var identifyAnswer: () -> List<SpeciesCandidateDto> = { listOf(candidate("Monstera deliciosa", 0.8)) }
        var identifyGate: CompletableDeferred<Unit>? = null
        var diagnoseAnswer: () -> DiagnosisResponse = {
            DiagnosisResponse(listOf(DiagnosisCauseDto("Too much water", "high", "Soft leaves", "Water less")), "Overwatered")
        }

        val identifier = SpeciesIdentifier { photo, lang ->
            identifyCalls += photo to lang
            identifyGate?.await()
            identifyAnswer()
        }
        val diagnoser = StandaloneDiagnoser { photo, note, lang ->
            diagnoseCalls += Triple(photo, note, lang)
            diagnoseAnswer()
        }

        val sends: Int get() = identifyCalls.size + diagnoseCalls.size

        fun controller(
            mode: SmartCameraMode = SmartCameraMode.Plant,
            withDiagnoser: Boolean = true,
        ) = SmartCameraController(
            initialMode = mode,
            identifier = identifier,
            diagnoser = if (withDiagnoser) diagnoser else null,
            pick = { source -> picks += source; pickResult },
            capture = { flash -> shots += flash; shotResult },
            preparePlant = { bytes -> bytes + 7 },
            prepareDisease = { bytes -> bytes + 9 },
            catalog = { null },
            onModeChange = { remembered += it },
        )
    }

    private companion object {
        fun candidate(name: String, score: Double, common: List<String> = emptyList()) =
            SpeciesCandidateDto(scientificName = name, commonNames = common, score = score)
    }

    private fun SmartCameraController.ready(hasFlash: Boolean = true) = onCameraStatus(CameraStatus.Ready(hasFlash))

    @Test
    fun modeFromWireDefaultsToPlant() {
        assertEquals(SmartCameraMode.Plant, SmartCameraMode.fromWire(null))
        assertEquals(SmartCameraMode.Plant, SmartCameraMode.fromWire("plant"))
        assertEquals(SmartCameraMode.Disease, SmartCameraMode.fromWire("disease"))
        assertEquals(SmartCameraMode.Plant, SmartCameraMode.fromWire("other"))
        assertEquals("disease", SmartCameraMode.Disease.wire)
    }

    @Test
    fun opensOnTheViewfinderAndTapOrSwipeChangeTheModeWithoutSending() = runTest {
        val f = Fakes()
        val c = f.controller()
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        assertEquals(CameraStatus.Starting, c.cameraStatus)
        assertEquals(SmartCameraMode.Plant, c.mode)
        c.selectMode(SmartCameraMode.Disease)
        assertEquals(SmartCameraMode.Disease, c.mode)
        // A swipe to the right goes back to «Растение», a swipe to the left goes on to «Болезнь».
        c.swipeMode(dragX = 120f)
        assertEquals(SmartCameraMode.Plant, c.mode)
        c.swipeMode(dragX = 120f)
        assertEquals(SmartCameraMode.Plant, c.mode, "no mode before «Растение»")
        c.swipeMode(dragX = -120f)
        assertEquals(SmartCameraMode.Disease, c.mode)
        c.swipeMode(dragX = -120f)
        assertEquals(SmartCameraMode.Disease, c.mode, "no mode after «Болезнь»")
        c.swipeMode(dragX = 4f)
        assertEquals(SmartCameraMode.Disease, c.mode, "a tiny drag is not a swipe")
        advanceUntilIdle()
        assertEquals(listOf(SmartCameraMode.Disease, SmartCameraMode.Plant, SmartCameraMode.Disease), f.remembered)
        assertEquals(0, f.sends)
        assertTrue(f.picks.isEmpty() && f.shots.isEmpty())
    }

    @Test
    fun theLastModeIsRememberedAcrossCloseAndReopen() {
        val memory = SmartCameraModeMemory()
        assertEquals(SmartCameraMode.Plant, memory.resolve(null))
        val first = Fakes().let {
            SmartCameraController(
                initialMode = memory.resolve(null),
                identifier = it.identifier,
                diagnoser = it.diagnoser,
                pick = { PickResult.Cancelled },
                preparePlant = { b -> b },
                prepareDisease = { b -> b },
                catalog = { null },
                onModeChange = memory::remember,
            )
        }
        first.selectMode(SmartCameraMode.Disease)
        first.close()
        assertEquals(SmartCameraMode.Disease, memory.resolve(null))
        // An explicit mode (plant detail «Что с растением?») wins over the remembered one.
        assertEquals(SmartCameraMode.Plant, memory.resolve(SmartCameraMode.Plant))
        memory.remember(SmartCameraMode.Plant)
        assertEquals(SmartCameraMode.Plant, memory.resolve(null))
    }

    @Test
    fun aSecondShutterOrModeChangeIsIgnoredWhileThePhotoIsBeingPrepared() = runTest {
        val f = Fakes()
        val scope = this
        lateinit var c: SmartCameraController
        var pickingDuringPrepare: Boolean? = null
        c = SmartCameraController(
            initialMode = SmartCameraMode.Plant,
            identifier = f.identifier,
            diagnoser = f.diagnoser,
            pick = { source -> f.picks += source; f.pickResult },
            capture = { flash -> f.shots += flash; f.shotResult },
            preparePlant = { bytes ->
                pickingDuringPrepare = c.picking
                c.shutter(scope)
                c.chooseFromGallery(scope)
                c.selectMode(SmartCameraMode.Disease)
                bytes
            },
            prepareDisease = { it },
            catalog = { null },
            onModeChange = { f.remembered += it },
        )
        c.ready()
        c.shutter(this)
        advanceUntilIdle()
        assertEquals(true, pickingDuringPrepare)
        assertEquals(1, f.shots.size, "one shot only")
        assertTrue(f.picks.isEmpty(), "no gallery pick while the shot is prepared")
        assertEquals(SmartCameraMode.Plant, c.mode)
        assertEquals(SmartCameraPhase.Captured, c.phase)
        assertFalse(c.picking)
        assertEquals(0, f.sends)
    }

    @Test
    fun theFlashChoiceSurvivesACameraRestartButNotACameraWithoutFlash() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.ready()
        c.toggleFlash()
        c.shutter(this)
        advanceUntilIdle()
        c.retake()
        // The viewfinder binds the camera again after «Переснять».
        c.onCameraStatus(CameraStatus.Starting)
        c.ready()
        assertTrue(c.flash, "the flash stays on after a retake")
        c.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        assertFalse(c.flash)
    }

    @Test
    fun theShutterTakesOnePhotoWithTheFlashStateAndShowsItWithoutSending() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.shutter(this)
        advanceUntilIdle()
        assertTrue(f.shots.isEmpty(), "no shot before the camera is ready")
        c.ready()
        assertFalse(c.flash)
        c.toggleFlash()
        assertTrue(c.flash)
        c.selectMode(SmartCameraMode.Disease)
        c.selectMode(SmartCameraMode.Plant)
        assertTrue(c.flash, "the flash state stays while the mode changes")
        c.shutter(this)
        advanceUntilIdle()
        assertEquals(listOf(true), f.shots)
        assertEquals(SmartCameraPhase.Captured, c.phase)
        assertTrue(byteArrayOf(4, 5, 6, 7).contentEquals(c.photo), "the prepared, upright photo is shown")
        assertEquals(0, f.sends)

        c.retake()
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        c.toggleFlash()
        c.shutter(this)
        advanceUntilIdle()
        assertEquals(listOf(true, false), f.shots)
        assertEquals(0, f.sends)
    }

    @Test
    fun withoutAFlashUnitTheToggleDoesNothing() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.ready(hasFlash = false)
        assertFalse(c.hasFlash)
        c.toggleFlash()
        assertFalse(c.flash)
        c.shutter(this)
        advanceUntilIdle()
        assertEquals(listOf(false), f.shots)
    }

    @Test
    fun plantSendIsExactlyOneIdentifyCallOnTheExplicitTap() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.ready()
        c.shutter(this)
        advanceUntilIdle()
        assertEquals(0, f.sends, "nothing before «Узнать вид»")
        c.send(this, "en")
        c.send(this, "en")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.PlantResult>(c.phase)
        assertEquals(1, f.identifyCalls.size)
        assertEquals(0, f.diagnoseCalls.size)
        assertEquals("en", f.identifyCalls[0].second)
        assertTrue(f.identifyCalls[0].first.startsWith("data:image/jpeg;base64,"))
        c.send(this, "en")
        advanceUntilIdle()
        assertEquals(1, f.identifyCalls.size, "a result is not sent again")
    }

    @Test
    fun diseaseSendIsExactlyOneDiagnoseCallWithTheTypedNote() = runTest {
        val f = Fakes()
        val c = f.controller(mode = SmartCameraMode.Disease)
        c.ready()
        c.shutter(this)
        advanceUntilIdle()
        c.updateNote("  Жёлтые пятна на листьях  ")
        advanceUntilIdle()
        assertEquals(0, f.sends, "typing a note sends nothing")
        c.send(this, "ru")
        advanceUntilIdle()
        val result = assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        assertEquals("Too much water", result.result.causes.single().title)
        val (photo, note, lang) = f.diagnoseCalls.single()
        assertTrue(photo.startsWith("data:image/jpeg;base64,"))
        assertEquals("Жёлтые пятна на листьях", note)
        assertEquals("ru", lang)
        assertTrue(f.identifyCalls.isEmpty())
        assertTrue(byteArrayOf(4, 5, 6, 9).contentEquals(c.photo))
    }

    @Test
    fun theNoteIsCappedAt500AndABlankNoteIsNotSent() = runTest {
        val f = Fakes()
        val c = f.controller(mode = SmartCameraMode.Disease)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.updateNote("a".repeat(DiagnosisLogic.MAX_NOTE_LENGTH + 40))
        assertEquals(DiagnosisLogic.MAX_NOTE_LENGTH, c.note.length)
        c.updateNote("   ")
        c.send(this, "en")
        advanceUntilIdle()
        assertNull(f.diagnoseCalls.single().second)
    }

    @Test
    fun aGalleryPickLeadsToTheSameAfterShotScreenInEachMode() = runTest {
        for (mode in SmartCameraMode.entries) {
            val f = Fakes()
            val c = f.controller(mode = mode)
            c.chooseFromGallery(this)
            advanceUntilIdle()
            assertEquals(listOf(PickSource.Gallery), f.picks)
            assertEquals(SmartCameraPhase.Captured, c.phase)
            assertEquals(mode, c.mode)
            assertTrue(c.photo != null)
            assertEquals(0, f.sends)
        }
    }

    @Test
    fun aCancelledPickOrShotStaysOnTheViewfinderInTheSameMode() = runTest {
        val f = Fakes()
        f.pickResult = PickResult.Cancelled
        f.shotResult = PickResult.Cancelled
        val c = f.controller(mode = SmartCameraMode.Disease)
        c.ready()
        c.chooseFromGallery(this)
        c.shutter(this)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        assertEquals(SmartCameraMode.Disease, c.mode)
        assertNull(c.photo)
        assertEquals(0, f.sends)
    }

    @Test
    fun retakeReturnsToTheViewfinderInTheSameModeForBothModes() = runTest {
        for (mode in SmartCameraMode.entries) {
            val f = Fakes()
            val c = f.controller(mode = mode)
            c.ready()
            c.shutter(this)
            advanceUntilIdle()
            c.updateNote("note")
            c.retake()
            advanceUntilIdle()
            assertEquals(SmartCameraPhase.Viewfinder, c.phase)
            assertEquals(mode, c.mode)
            assertNull(c.photo)
            assertEquals("note", c.note, "«Переснять» keeps the note of this camera session")
            assertEquals(0, f.sends)
        }
    }

    @Test
    fun theNoteIsKeptOnRetakeAndClearedAfterAResultOrOnClose() = runTest {
        val f = Fakes()
        val c = f.controller(mode = SmartCameraMode.Disease)
        c.ready()
        c.shutter(this)
        advanceUntilIdle()
        c.updateNote("Yellow leaves")
        c.retake()
        // A late text commit of the field that just left the screen does not change anything.
        c.updateNote("Yellow leaves, typed late")
        assertEquals("Yellow leaves", c.note)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        assertEquals("Yellow leaves", c.note, "the next photo of the session keeps the note")
        c.send(this, "en")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        assertEquals("Yellow leaves", f.diagnoseCalls.single().second)
        assertEquals("", c.note, "a shown result clears the note")
        c.updateNote("Yellow leaves")
        assertEquals("", c.note, "the result screen has no note field")
        c.retake()
        c.chooseFromGallery(this)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Captured, c.phase)
        assertEquals("", c.note, "the next new disease photo starts without the old note")

        c.updateNote("Spots")
        c.close()
        assertEquals("", c.note, "closing the camera clears the note")
        val reopened = f.controller(mode = SmartCameraMode.Disease)
        assertEquals("", reopened.note)
    }

    @Test
    fun aFailedSendKeepsTheNote() = runTest {
        val f = Fakes()
        f.diagnoseAnswer = { throw ApiError.Network }
        val c = f.controller(mode = SmartCameraMode.Disease)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.updateNote("Spots")
        c.send(this, "ru")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.Failed>(c.phase)
        assertEquals("Spots", c.note, "a failure is no result: the note stays for the next try")
    }

    @Test
    fun noPhotoLeavesBeforeTheTapAcrossTheWholeSequence() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.ready()
        c.selectMode(SmartCameraMode.Disease)
        c.swipeMode(dragX = 200f)
        for (mode in SmartCameraMode.entries) {
            c.selectMode(mode)
            c.shutter(this)
            advanceUntilIdle()
            c.updateNote("leaves")
            c.retake()
            c.chooseFromGallery(this)
            advanceUntilIdle()
            c.retake()
        }
        c.close()
        advanceUntilIdle()
        assertEquals(0, f.identifyCalls.size)
        assertEquals(0, f.diagnoseCalls.size)
    }

    @Test
    fun modeAndFlashCannotChangeOutsideTheViewfinder() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.selectMode(SmartCameraMode.Disease)
        c.swipeMode(dragX = -300f)
        assertEquals(SmartCameraMode.Plant, c.mode)
        assertTrue(f.remembered.isEmpty())
    }

    @Test
    fun aDeniedCameraOffersTheGalleryAndSendsNothing() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.onCameraStatus(CameraStatus.Denied(permanent = false))
        c.shutter(this)
        advanceUntilIdle()
        assertTrue(f.shots.isEmpty())
        c.onCameraStatus(CameraStatus.Denied(permanent = true))
        assertEquals(CameraStatus.Denied(permanent = true), c.cameraStatus)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Captured, c.phase)
        assertEquals(0, f.sends)
        c.retake()
        assertEquals(CameraStatus.Denied(permanent = true), c.cameraStatus, "retake keeps the camera state")
    }

    @Test
    fun anUnavailableCameraFallsBackToTheSystemCameraAndTheGallery() = runTest {
        val f = Fakes()
        f.pickResult = PickResult.Picked(byteArrayOf(8), capturePath = "/cache/gt-pick/gt-diag-2.jpg")
        val c = f.controller()
        c.onCameraStatus(CameraStatus.Unavailable)
        c.shutter(this)
        advanceUntilIdle()
        assertTrue(f.shots.isEmpty())
        c.useSystemCamera(this)
        advanceUntilIdle()
        assertEquals(listOf(PickSource.Camera), f.picks)
        assertEquals(SmartCameraPhase.Captured, c.phase)
        assertEquals(0, f.sends)
        c.retake()
        f.pickResult = PickResult.Picked(byteArrayOf(9))
        c.chooseFromGallery(this)
        advanceUntilIdle()
        assertEquals(listOf(PickSource.Camera, PickSource.Gallery), f.picks)
        assertEquals(SmartCameraPhase.Captured, c.phase)
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals(1, f.identifyCalls.size)
    }

    @Test
    fun theSystemCameraIsOnlyAFallback() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.ready()
        c.useSystemCamera(this)
        advanceUntilIdle()
        assertTrue(f.picks.isEmpty(), "the system camera is not used while CameraX runs")
    }

    @Test
    fun aFailingShotShowsAnErrorAndStaysOnTheViewfinder() = runTest {
        val f = Fakes()
        val c = SmartCameraController(
            initialMode = SmartCameraMode.Plant,
            identifier = f.identifier,
            diagnoser = f.diagnoser,
            pick = { PickResult.Cancelled },
            capture = { error("capture failed") },
            preparePlant = { it },
            prepareDisease = { it },
            catalog = { null },
        )
        c.ready()
        c.shutter(this)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        assertEquals("capture failed", c.pickErrorMessage)
        assertFalse(c.picking)
    }

    @Test
    fun aGalleryPermissionDenialStaysOnTheViewfinder() = runTest {
        val f = Fakes()
        f.pickResult = PickResult.PermissionDenied(PickSource.Gallery)
        val c = f.controller()
        c.chooseFromGallery(this)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        assertEquals(PickSource.Gallery, c.deniedSource)
    }

    @Test
    fun plantModeShowsAtMostThreeCandidatesInServerOrder() = runTest {
        val f = Fakes()
        f.identifyAnswer = {
            listOf(
                candidate("Monstera deliciosa", 0.81, listOf("Swiss cheese plant")),
                candidate("Philodendron bipinnatifidum", 0.3),
                candidate("Epipremnum aureum", 0.05),
                candidate("Rhaphidophora tetrasperma", 0.01),
            )
        }
        val c = f.controller()
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "en")
        advanceUntilIdle()
        val result = assertIs<SmartCameraPhase.PlantResult>(c.phase)
        assertEquals(
            listOf("Monstera deliciosa", "Philodendron bipinnatifidum", "Epipremnum aureum"),
            result.candidates.map { it.scientificName },
        )
        assertEquals(listOf(ScoreLevel.High, ScoreLevel.Medium, ScoreLevel.Low), result.candidates.map { it.level })
        assertEquals("Swiss cheese plant", result.candidates[0].commonName)
        // The prepared photo (not the raw pick) is kept for the result and the add-plant draft.
        assertTrue(byteArrayOf(1, 2, 3, 7).contentEquals(c.photo))
        assertEquals(0, c.selectedCandidate)
    }

    @Test
    fun loadingIsShownWhileIdentifyRunsAndCancelReturnsToTheAfterShotScreen() = runTest {
        val f = Fakes()
        f.identifyGate = CompletableDeferred()
        val c = f.controller()
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Loading, c.phase)
        c.cancel()
        f.identifyGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Captured, c.phase, "the photo stays for another try")
        assertTrue(c.photo != null)
        assertEquals(SmartCameraMode.Plant, c.mode)
        assertEquals(1, f.identifyCalls.size)
    }

    @Test
    fun emptyAnswerIsNoMatchAndErrorsAreMapped() = runTest {
        val f = Fakes()
        f.identifyAnswer = { emptyList() }
        val c = f.controller()
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Failed(SmartCameraFailure.NoMatch), c.phase)
        c.retake()
        f.identifyAnswer = { throw ApiError.Client(429, """{"error":"quota_exceeded"}""") }
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Failed(SmartCameraFailure.IdentifyQuota), c.phase)
        c.retake()
        f.identifyAnswer = { throw ApiError.Server(502, "") }
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Failed(SmartCameraFailure.IdentifyUnavailable), c.phase)
        c.retake()
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
    }

    @Test
    fun retakeFromAResultKeepsTheModeAndSendsNothingMore() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.ready()
        c.shutter(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.PlantResult>(c.phase)
        c.retake()
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        assertEquals(SmartCameraMode.Plant, c.mode)
        assertEquals(1, f.identifyCalls.size)
    }

    @Test
    fun selectCandidateIsBoundedByTheResult() = runTest {
        val f = Fakes()
        f.identifyAnswer = { listOf(candidate("A a", 0.6), candidate("B b", 0.3)) }
        val c = f.controller()
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        c.selectCandidate(1)
        assertEquals(1, c.selectedCandidate)
        c.selectCandidate(5)
        assertEquals(1, c.selectedCandidate)
        assertEquals("B b", c.chosenCandidate()?.scientificName)
    }

    @Test
    fun diseaseModeWithoutDiagnoserIsUnavailable() = runTest {
        val f = Fakes()
        val c = f.controller(mode = SmartCameraMode.Disease, withDiagnoser = false)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Failed(SmartCameraFailure.Diagnosis(DiagnosisError.Unavailable)), c.phase)
        assertTrue(f.identifyCalls.isEmpty())
    }

    @Test
    fun closeDropsThePhotoAndIgnoresLateAnswers() = runTest {
        val f = Fakes()
        f.identifyGate = CompletableDeferred()
        val c = f.controller()
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.send(this, "ru")
        advanceUntilIdle()
        c.close()
        f.identifyGate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(c.photo)
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        assertEquals(1, f.picks.size, "a closed camera picks nothing")
    }
}
