package site.xmpp.greenthumb.ui.screens.smartcam

import kotlin.test.Test
import kotlin.test.assertEquals
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
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisError
import site.xmpp.greenthumb.ui.screens.species.ScoreLevel
import site.xmpp.greenthumb.ui.screens.species.SpeciesIdentifier

/** VAL-CAM-003/004/005/006/008: the smart camera flow without UI. */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartCameraControllerTest {

    private class Fakes {
        val identifyCalls = mutableListOf<Pair<String, String>>()
        val diagnoseCalls = mutableListOf<Pair<String, String>>()
        val picks = mutableListOf<PickSource>()
        var pickResult: PickResult = PickResult.Picked(byteArrayOf(1, 2, 3))
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
        val diagnoser = StandaloneDiagnoser { photo, _, lang ->
            diagnoseCalls += photo to lang
            diagnoseAnswer()
        }

        fun controller(
            mode: SmartCameraMode = SmartCameraMode.Plant,
            withDiagnoser: Boolean = true,
        ) = SmartCameraController(
            initialMode = mode,
            identifier = identifier,
            diagnoser = if (withDiagnoser) diagnoser else null,
            pick = { source -> picks += source; pickResult },
            preparePlant = { bytes -> bytes + 7 },
            prepareDisease = { bytes -> bytes + 9 },
            catalog = { null },
        )
    }

    private companion object {
        fun candidate(name: String, score: Double, common: List<String> = emptyList()) =
            SpeciesCandidateDto(scientificName = name, commonNames = common, score = score)
    }

    @Test
    fun modeFromWireDefaultsToPlant() {
        assertEquals(SmartCameraMode.Plant, SmartCameraMode.fromWire(null))
        assertEquals(SmartCameraMode.Plant, SmartCameraMode.fromWire("plant"))
        assertEquals(SmartCameraMode.Disease, SmartCameraMode.fromWire("disease"))
        assertEquals(SmartCameraMode.Plant, SmartCameraMode.fromWire("other"))
        assertEquals("disease", SmartCameraMode.Disease.wire)
    }

    @Test
    fun startsInChooseAndSwitchingModeSendsNothing() = runTest {
        val f = Fakes()
        val c = f.controller()
        assertEquals(SmartCameraPhase.Choose, c.phase)
        assertEquals(SmartCameraMode.Plant, c.mode)
        c.selectMode(SmartCameraMode.Disease)
        assertEquals(SmartCameraMode.Disease, c.mode)
        c.selectMode(SmartCameraMode.Plant)
        advanceUntilIdle()
        assertEquals(SmartCameraMode.Plant, c.mode)
        assertTrue(f.identifyCalls.isEmpty() && f.diagnoseCalls.isEmpty() && f.picks.isEmpty())
    }

    @Test
    fun cancelledPickReturnsToChooseWithTheSameModeAndSendsNothing() = runTest {
        val f = Fakes()
        f.pickResult = PickResult.Cancelled
        val c = f.controller()
        c.selectMode(SmartCameraMode.Disease)
        c.shoot(this, PickSource.Camera, "ru")
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Choose, c.phase)
        assertEquals(SmartCameraMode.Disease, c.mode)
        assertEquals(listOf(PickSource.Camera), f.picks)
        assertTrue(f.identifyCalls.isEmpty() && f.diagnoseCalls.isEmpty())
        assertNull(c.photo)
    }

    @Test
    fun permissionDeniedStaysInChoose() = runTest {
        val f = Fakes()
        f.pickResult = PickResult.PermissionDenied(PickSource.Camera)
        val c = f.controller()
        c.shoot(this, PickSource.Camera, "en")
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Choose, c.phase)
        assertEquals(PickSource.Camera, c.deniedSource)
        assertTrue(f.identifyCalls.isEmpty())
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
        c.shoot(this, PickSource.Gallery, "en")
        advanceUntilIdle()
        val result = assertIs<SmartCameraPhase.PlantResult>(c.phase)
        assertEquals(
            listOf("Monstera deliciosa", "Philodendron bipinnatifidum", "Epipremnum aureum"),
            result.candidates.map { it.scientificName },
        )
        assertEquals(listOf(ScoreLevel.High, ScoreLevel.Medium, ScoreLevel.Low), result.candidates.map { it.level })
        assertEquals("Swiss cheese plant", result.candidates[0].commonName)
        assertEquals(1, f.identifyCalls.size)
        assertEquals("en", f.identifyCalls[0].second)
        assertTrue(f.identifyCalls[0].first.startsWith("data:image/jpeg;base64,"))
        assertTrue(f.diagnoseCalls.isEmpty())
        // The prepared photo (not the raw pick) is kept for the result and the add-plant draft.
        assertTrue(byteArrayOf(1, 2, 3, 7).contentEquals(c.photo))
        assertEquals(0, c.selectedCandidate)
    }

    @Test
    fun loadingIsShownWhileIdentifyRuns() = runTest {
        val f = Fakes()
        f.identifyGate = CompletableDeferred()
        val c = f.controller()
        c.shoot(this, PickSource.Gallery, "ru")
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Loading, c.phase)
        f.identifyGate!!.complete(Unit)
        advanceUntilIdle()
        assertIs<SmartCameraPhase.PlantResult>(c.phase)
    }

    @Test
    fun cancelDuringLoadingReturnsToChooseAndDropsTheAnswer() = runTest {
        val f = Fakes()
        f.identifyGate = CompletableDeferred()
        val c = f.controller()
        c.shoot(this, PickSource.Gallery, "ru")
        advanceUntilIdle()
        c.cancel()
        f.identifyGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Choose, c.phase)
        assertEquals(SmartCameraMode.Plant, c.mode)
    }

    @Test
    fun emptyAnswerIsNoMatch() = runTest {
        val f = Fakes()
        f.identifyAnswer = { emptyList() }
        val c = f.controller()
        c.shoot(this, PickSource.Gallery, "ru")
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Failed(SmartCameraFailure.NoMatch), c.phase)
    }

    @Test
    fun identify429IsQuotaAndOtherErrorsAreUnavailable() = runTest {
        val f = Fakes()
        f.identifyAnswer = { throw ApiError.Client(429, """{"error":"quota_exceeded"}""") }
        val c = f.controller()
        c.shoot(this, PickSource.Gallery, "ru")
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Failed(SmartCameraFailure.IdentifyQuota), c.phase)
        c.retake()
        f.identifyAnswer = { throw ApiError.Server(502, "") }
        c.shoot(this, PickSource.Gallery, "ru")
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Failed(SmartCameraFailure.IdentifyUnavailable), c.phase)
    }

    @Test
    fun retakeFromResultKeepsTheModeAndSendsNothing() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.shoot(this, PickSource.Camera, "ru")
        advanceUntilIdle()
        assertIs<SmartCameraPhase.PlantResult>(c.phase)
        c.retake()
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Choose, c.phase)
        assertEquals(SmartCameraMode.Plant, c.mode)
        assertEquals(1, f.identifyCalls.size)
    }

    @Test
    fun modeCannotChangeOutsideChoose() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.shoot(this, PickSource.Gallery, "ru")
        advanceUntilIdle()
        c.selectMode(SmartCameraMode.Disease)
        assertEquals(SmartCameraMode.Plant, c.mode)
    }

    @Test
    fun selectCandidateIsBoundedByTheResult() = runTest {
        val f = Fakes()
        f.identifyAnswer = { listOf(candidate("A a", 0.6), candidate("B b", 0.3)) }
        val c = f.controller()
        c.shoot(this, PickSource.Gallery, "ru")
        advanceUntilIdle()
        c.selectCandidate(1)
        assertEquals(1, c.selectedCandidate)
        c.selectCandidate(5)
        assertEquals(1, c.selectedCandidate)
        assertEquals("B b", c.chosenCandidate()?.scientificName)
    }

    @Test
    fun diseaseModeUsesTheStandaloneDiagnoserAndNeverIdentify() = runTest {
        val f = Fakes()
        val c = f.controller(mode = SmartCameraMode.Disease)
        c.shoot(this, PickSource.Gallery, "en")
        advanceUntilIdle()
        val result = assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        assertEquals("Too much water", result.result.causes.single().title)
        assertEquals(1, f.diagnoseCalls.size)
        assertEquals("en", f.diagnoseCalls[0].second)
        assertTrue(f.identifyCalls.isEmpty())
        assertTrue(byteArrayOf(1, 2, 3, 9).contentEquals(c.photo))
    }

    @Test
    fun diseaseModeWithoutDiagnoserIsUnavailable() = runTest {
        val f = Fakes()
        val c = f.controller(mode = SmartCameraMode.Disease, withDiagnoser = false)
        c.shoot(this, PickSource.Gallery, "ru")
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Failed(SmartCameraFailure.Diagnosis(DiagnosisError.Unavailable)), c.phase)
        assertTrue(f.identifyCalls.isEmpty())
    }

    @Test
    fun closeDropsThePhotoAndIgnoresLateAnswers() = runTest {
        val f = Fakes()
        f.identifyGate = CompletableDeferred()
        val c = f.controller()
        c.shoot(this, PickSource.Gallery, "ru")
        advanceUntilIdle()
        c.close()
        f.identifyGate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(c.photo)
        assertEquals(SmartCameraPhase.Choose, c.phase)
        c.shoot(this, PickSource.Gallery, "ru")
        advanceUntilIdle()
        assertEquals(1, f.picks.size)
    }
}
