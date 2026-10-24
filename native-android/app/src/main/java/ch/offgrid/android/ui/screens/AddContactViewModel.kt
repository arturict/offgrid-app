package ch.offgrid.android.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.offgrid.android.domain.AntennaState
import ch.offgrid.android.domain.Pairing
import ch.offgrid.android.domain.PairingError
import ch.offgrid.android.domain.PairingState
import ch.offgrid.android.ui.components.Step
import ch.offgrid.android.ui.components.StepState
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the [PairingWizard] of "Kontakt hinzufügen". It lives as long as the screen's back stack entry, so rotating
 * the phone keeps the step, the QR code and the scan progress. Leaving the screen without "Abbrechen" keeps the
 * antenna's session (see [PairingWizard.dispose]). After process death a new instance starts at step 1.
 */
class AddContactViewModel(
    pairing: Pairing,
    antenna: StateFlow<AntennaState>,
    discardContact: (Long) -> Unit,
    startWithScan: Boolean,
) : ViewModel() {
    val wizard = PairingWizard(pairing, antenna, viewModelScope, discardContact, startWithScan = startWithScan)

    override fun onCleared() = wizard.dispose()
}

/** What "Kontakt hinzufügen" shows for a pairing state and the antenna connection. */
data class AddContactUi(
    /** QR text to render; null while it loads or while the antenna is away and the code may be stale. */
    val qr: String?,
    val error: PairingError?,
    /** "Neu koppeln" is offered for a contact the antenna already has. */
    val canReplace: Boolean = false,
    val confirmedContactId: Long?,
) {
    val confirmed: Boolean get() = confirmedContactId != null
}

fun addContactUi(pairing: PairingState, antennaReady: Boolean): AddContactUi {
    val scanned = pairing.scannedName != null
    return AddContactUi(
        // After our own scan the code stays valid without the antenna (see PairingWizard.onReconnected).
        qr = pairing.qr.takeIf { antennaReady || scanned },
        // Pairing only notices a lost antenna at its next refresh, so the screen reports it at once.
        error = pairing.error ?: PairingError.ANTENNA_NOT_READY.takeIf { !antennaReady },
        canReplace = pairing.replaceCode != null && antennaReady,
        confirmedContactId = pairing.confirmedContactId,
    )
}

/** Steps after our own scan as the second half, while the radio confirms. */
fun scanSteps(pingSent: Boolean, confirmed: Boolean): List<Step> {
    // The peer's own packet confirms the contact even when our ping was refused by the firmware.
    val sent = pingSent || confirmed
    return listOf(
        Step("Code gescannt", StepState.Done, meta = "erledigt"),
        Step("Schlüssel per Funk gesendet", if (sent) StepState.Done else StepState.Active, meta = if (sent) "erledigt" else "sendet …"),
        Step(
            "Bestätigung empfangen",
            when {
                confirmed -> StepState.Done
                sent -> StepState.Active
                else -> StepState.Todo
            },
            meta = if (sent && !confirmed) "wartet …" else null,
        ),
    )
}
