package site.xmpp.greenthumb.ui.screens.smartcam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import site.xmpp.greenthumb.core.network.DiagnosisCauseDto
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournalSaver
import site.xmpp.greenthumb.ui.screens.diagnosis.JournalSaveState

/** Shows the photo from the gallery and taps the send button. */
private fun SmartCameraController.sendFromGallery(scope: kotlinx.coroutines.test.TestScope, language: String) {
    chooseFromGallery(scope)
    scope.advanceUntilIdle()
    send(scope, language)
    scope.advanceUntilIdle()
}

/** Takes a photo with the in-app camera and taps the send button. */
private fun SmartCameraController.sendShot(scope: kotlinx.coroutines.test.TestScope, language: String) {
    onCameraStatus(CameraStatus.Ready(hasFlash = false))
    shutter(scope)
    scope.advanceUntilIdle()
    send(scope, language)
    scope.advanceUntilIdle()
}

/** VAL-CAM-010 / VAL-CAM-018 / VAL-CAM11-018 without UI: «Это моё растение?» saves once, no second diagnosis; capture files are deleted. */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartCameraJournalTest {

    private class Fakes {
        var diagnoseCalls = 0
        val saves = mutableListOf<Triple<String, String, String>>()
        val deleted = mutableListOf<String>()
        var saveGate: CompletableDeferred<Unit>? = null
        var saveFails = false
        var pickResult: PickResult = PickResult.Picked(byteArrayOf(1, 2, 3), capturePath = "/cache/shot-1.jpg")

        val diagnoser = StandaloneDiagnoser { _, _, _ ->
            diagnoseCalls++
            DiagnosisResponse(listOf(DiagnosisCauseDto("Too much water", "high", "Soft leaves", "Water less")), "Overwatered")
        }
        val journal = DiagnosisJournalSaver { plantId, uri, caption ->
            saveGate?.await()
            if (saveFails) error("offline")
            saves += Triple(plantId, uri, caption)
        }

        fun controller(withJournal: Boolean = true, mode: SmartCameraMode = SmartCameraMode.Disease) = SmartCameraController(
            initialMode = mode,
            identifier = { _, _ -> emptyList() },
            diagnoser = diagnoser,
            pick = { pickResult },
            capture = { pickResult },
            preparePlant = { it + 7 },
            prepareDisease = { it + 9 },
            catalog = { null },
            journal = if (withJournal) journal else null,
            deleteCapture = { deleted += it },
        )
    }

    @Test
    fun choosingAPlantThenSavingUploadsTheDiagnosedPhotoOnceWithoutASecondDiagnosis() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.sendFromGallery(this, "ru")
        assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        assertTrue(c.canSaveToJournal)
        assertNull(c.journalPlantId)

        // Without a chosen plant nothing is saved.
        c.saveToJournal(this, "caption")
        advanceUntilIdle()
        assertTrue(f.saves.isEmpty())

        c.openPlantPicker()
        assertTrue(c.plantPickerOpen)
        c.choosePlant("p-2")
        assertFalse(c.plantPickerOpen)
        assertEquals("p-2", c.journalPlantId)
        assertEquals(JournalSaveState.Idle, c.journalSave)

        c.saveToJournal(this, "Что с растением?: Too much water (Скорее всего)")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Saved, c.journalSave)
        val (plantId, uri, caption) = f.saves.single()
        assertEquals("p-2", plantId)
        assertEquals(photoDataUri(byteArrayOf(1, 2, 3, 9)), uri, "the diagnosed photo is uploaded")
        assertEquals("Что с растением?: Too much water (Скорее всего)", caption)

        // A second tap and a later plant change do nothing.
        c.saveToJournal(this, "again")
        c.openPlantPicker()
        c.choosePlant("p-3")
        advanceUntilIdle()
        assertEquals(1, f.saves.size)
        assertFalse(c.plantPickerOpen)
        assertEquals("p-2", c.journalPlantId)
        assertEquals(1, f.diagnoseCalls, "saving makes no diagnosis request")
    }

    @Test
    fun tapsWhileSavingAreIgnored() = runTest {
        val f = Fakes()
        f.saveGate = CompletableDeferred()
        val c = f.controller()
        c.sendFromGallery(this, "en")
        c.choosePlant("p-1")
        c.saveToJournal(this, "x")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Saving, c.journalSave)
        c.saveToJournal(this, "x")
        f.saveGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, f.saves.size)
        assertEquals(JournalSaveState.Saved, c.journalSave)
    }

    @Test
    fun aFailedSaveCanBeRetriedAndAnotherPlantChosen() = runTest {
        val f = Fakes()
        f.saveFails = true
        val c = f.controller()
        c.sendFromGallery(this, "ru")
        c.choosePlant("p-1")
        c.saveToJournal(this, "x")
        advanceUntilIdle()
        assertEquals(JournalSaveState.Failed, c.journalSave)
        c.choosePlant("p-4")
        assertEquals(JournalSaveState.Idle, c.journalSave)
        f.saveFails = false
        c.saveToJournal(this, "x")
        advanceUntilIdle()
        assertEquals(listOf("p-4"), f.saves.map { it.first })
        assertEquals(1, f.diagnoseCalls)
    }

    @Test
    fun withoutAJournalOrOutsideADiseaseResultThereIsNoPicker() = runTest {
        val f = Fakes()
        val none = f.controller(withJournal = false)
        none.sendFromGallery(this, "ru")
        assertFalse(none.canSaveToJournal)
        none.openPlantPicker()
        none.choosePlant("p-1")
        none.saveToJournal(this, "x")
        advanceUntilIdle()
        assertFalse(none.plantPickerOpen)
        assertNull(none.journalPlantId)

        val choosing = f.controller()
        choosing.openPlantPicker()
        choosing.choosePlant("p-1")
        assertFalse(choosing.plantPickerOpen)
        assertNull(choosing.journalPlantId)
        assertTrue(f.saves.isEmpty())
    }

    @Test
    fun isMineIsOfferedOnlyUntilAPlantIsChosen() = runTest {
        val f = Fakes()
        val c = f.controller()
        assertFalse(c.canChooseOwnPlant, "nothing to choose before a result")
        c.sendFromGallery(this, "ru")
        assertTrue(c.canChooseOwnPlant, "a plant-less result offers «Это моё растение?»")
        c.openPlantPicker()
        c.choosePlant("p-2")
        assertFalse(c.canChooseOwnPlant, "the save button for the chosen plant replaces «Это моё растение?»")
        c.retake()
        assertFalse(c.canChooseOwnPlant)

        val none = f.controller(withJournal = false)
        none.sendFromGallery(this, "ru")
        assertFalse(none.canChooseOwnPlant, "no journal, no choice")
    }

    @Test
    fun retakeForgetsTheChosenPlant() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.sendFromGallery(this, "ru")
        c.choosePlant("p-1")
        c.retake()
        assertNull(c.journalPlantId)
        assertEquals(JournalSaveState.Idle, c.journalSave)
        c.saveToJournal(this, "x")
        advanceUntilIdle()
        assertTrue(f.saves.isEmpty())
    }

    @Test
    fun theCameraShotStaysWhileOpenAndIsDeletedOnRetakeAndClose() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.sendShot(this, "ru")
        assertIs<SmartCameraPhase.DiseaseResult>(c.phase)
        assertTrue(f.deleted.isEmpty(), "kept while the result is open")
        c.retake()
        assertEquals(listOf("/cache/shot-1.jpg"), f.deleted)

        f.pickResult = PickResult.Picked(byteArrayOf(4), capturePath = "/cache/shot-2.jpg")
        c.sendShot(this, "ru")
        c.close()
        assertEquals(listOf("/cache/shot-1.jpg", "/cache/shot-2.jpg"), f.deleted)
        c.close()
        assertEquals(2, f.deleted.size, "deleted once")
    }

    @Test
    fun anUnsentShotIsDeletedOnRetakeAndOnClose() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.onCameraStatus(CameraStatus.Ready(hasFlash = true))
        c.shutter(this)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Captured, c.phase)
        assertTrue(f.deleted.isEmpty(), "kept while the after-shot screen is open")
        c.retake()
        assertEquals(listOf("/cache/shot-1.jpg"), f.deleted)

        f.pickResult = PickResult.Picked(byteArrayOf(4), capturePath = "/cache/shot-2.jpg")
        c.shutter(this)
        advanceUntilIdle()
        c.close()
        assertEquals(listOf("/cache/shot-1.jpg", "/cache/shot-2.jpg"), f.deleted)
        assertEquals(0, f.diagnoseCalls)
    }

    @Test
    fun aShotThatArrivesAfterRetakeIsDeletedAndNotShown() = runTest {
        val f = Fakes()
        val gate = CompletableDeferred<PickResult>()
        val c = SmartCameraController(
            initialMode = SmartCameraMode.Plant,
            identifier = { _, _ -> emptyList() },
            diagnoser = null,
            pick = { PickResult.Cancelled },
            capture = { withContext(NonCancellable) { gate.await() } },
            preparePlant = { it },
            prepareDisease = { it },
            catalog = { null },
            deleteCapture = { f.deleted += it },
        )
        c.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        c.shutter(this)
        advanceUntilIdle()
        c.retake()
        gate.complete(PickResult.Picked(byteArrayOf(1), capturePath = "/cache/stale.jpg"))
        advanceUntilIdle()
        assertEquals(listOf("/cache/stale.jpg"), f.deleted)
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        assertNull(c.photo)
    }

    @Test
    fun aFailedRequestKeepsTheShotUntilTheScreenCloses() = runTest {
        val f = Fakes()
        val c = SmartCameraController(
            initialMode = SmartCameraMode.Disease,
            identifier = null,
            diagnoser = { _, _, _ -> throw site.xmpp.greenthumb.core.network.ApiError.Network },
            pick = { f.pickResult },
            capture = { f.pickResult },
            preparePlant = { it },
            prepareDisease = { it },
            catalog = { null },
            deleteCapture = { f.deleted += it },
        )
        c.sendShot(this, "ru")
        assertIs<SmartCameraPhase.Failed>(c.phase)
        assertTrue(f.deleted.isEmpty())
        c.close()
        assertEquals(listOf("/cache/shot-1.jpg"), f.deleted)
    }

    @Test
    fun aShotThatArrivesAfterCloseIsDeletedAtOnce() = runTest {
        val f = Fakes()
        val gate = CompletableDeferred<PickResult>()
        val c = SmartCameraController(
            initialMode = SmartCameraMode.Plant,
            identifier = { _, _ -> emptyList() },
            diagnoser = null,
            pick = { PickResult.Cancelled },
            // A camera that finishes writing its file even after the screen is gone.
            capture = { withContext(NonCancellable) { gate.await() } },
            preparePlant = { it },
            prepareDisease = { it },
            catalog = { null },
            deleteCapture = { f.deleted += it },
        )
        c.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        c.shutter(this)
        advanceUntilIdle()
        c.close()
        gate.complete(PickResult.Picked(byteArrayOf(1), capturePath = "/cache/late.jpg"))
        advanceUntilIdle()
        assertEquals(listOf("/cache/late.jpg"), f.deleted)
        assertNull(c.photo)
    }
}
