package ch.offgrid.android.ui.screens

import ch.offgrid.android.domain.PairingError
import ch.offgrid.android.domain.PairingState
import ch.offgrid.android.ui.components.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddContactUiTest {
    private val shown = PairingState(qr = "T0cx")

    @Test
    fun lostAntennaHidesTheCodeAndReportsItBeforeTheNextRefresh() {
        val ui = addContactUi(shown, antennaReady = false)
        assertNull(ui.qr)
        assertEquals(PairingError.ANTENNA_NOT_READY, ui.error)
    }

    @Test
    fun codeStaysAfterOwnScanBecauseThePeerStillNeedsIt() {
        val ui = addContactUi(shown.copy(scannedName = "Anna"), antennaReady = false)
        assertEquals("T0cx", ui.qr)
    }

    @Test
    fun pairingErrorWinsOverTheAntennaHint() {
        assertEquals(PairingError.SELF_PAIR, addContactUi(shown.copy(error = PairingError.SELF_PAIR), antennaReady = false).error)
        assertNull(addContactUi(shown, antennaReady = true).error)
    }

    @Test
    fun scanStepsFollowThePingAndTheConfirmation() {
        assertEquals(listOf(StepState.Done, StepState.Active, StepState.Todo), scanSteps(false, false).map { it.state })
        val sent = scanSteps(pingSent = true, confirmed = false)
        assertEquals(listOf(StepState.Done, StepState.Done, StepState.Active), sent.map { it.state })
        assertEquals("wartet …", sent[2].meta)
    }

    @Test
    fun confirmationCompletesTheSendStepEvenWhenThePingWasRefused() {
        assertEquals(List(3) { StepState.Done }, scanSteps(pingSent = false, confirmed = true).map { it.state })
    }
}
