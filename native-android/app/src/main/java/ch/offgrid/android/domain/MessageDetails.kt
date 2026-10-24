package ch.offgrid.android.domain

import ch.offgrid.android.data.MessageEntity
import ch.offgrid.android.data.MessageStatus

/**
 * The "Details" sheet of a message: what the antenna reported about its way over the air. Outgoing messages
 * carry the values of `msg.send` and `msg.status` (retries, and on delivery hops and the time to the ACK),
 * incoming ones those of `msg.received` (hops, RSSI, SNR, RETRY of the copy). Values the firmware did not
 * report are left out; the team firmware has no `compressed` and no delivery hops.
 */
object MessageDetails {
    /** Status in words, more precise than the meta line under the bubble. */
    fun status(m: MessageEntity, recipient: String? = null): String {
        if (m.direction == "in") return "Empfangen"
        return when (m.status) {
            MessageStatus.DELIVERED -> if (m.deliveredOffline) "Zugestellt, während offline" else "Zugestellt"
            MessageStatus.SENT -> when (m.deviceState) {
                "relayed" -> "Von einem anderen Gerät weitergeleitet"
                "retrying" -> "Gesendet, wird wiederholt"
                else -> if (m.groupId != null) "An die Gruppe gesendet" else "Gesendet, noch keine Bestätigung"
            }
            MessageStatus.WAITING -> "Wartet auf ${recipient ?: "Empfänger"}"
            MessageStatus.DEVICE_QUEUED -> "In der Warteschlange der Antenne"
            MessageStatus.LOCAL_QUEUED -> "Auf dem Handy, noch nicht an der Antenne"
            MessageStatus.FAILED -> if (failedBeforeSending(m.failReason)) "Nicht gesendet" else "Nicht zugestellt"
            else -> "Unbekannt"
        }
    }

    /** True when the antenna refused the message (an `E_…` code from `msg.send` or `group.send`, or no contact or group slot). */
    fun failedBeforeSending(reason: String?): Boolean =
        reason != null && (reason.startsWith("E_") || reason == "no_contact" || reason == "no_group")

    /** Why a message failed, for the details sheet. */
    fun reason(reason: String?): String? = when (reason) {
        null -> null
        "timeout", "no_ack" -> "Keine Bestätigung nach allen Versuchen"
        OutboxReconciler.REASON_LOST -> "Die Antenne kennt die Nachricht nicht mehr (Neustart oder neu verbunden)"
        "cancelled" -> "Abgebrochen"
        "locked" -> "Die Antenne wurde gesperrt"
        "contact_deleted" -> "Kontakt auf der Antenne gelöscht"
        "radio_error" -> "Funkfehler der Antenne"
        "no_contact" -> "Kontakt ist nicht auf der Antenne"
        "no_group" -> "Gruppe ist nicht auf der Antenne"
        "E_TOO_LONG" -> "Zu lang für ein Funkpaket"
        "E_BUSY" -> "Zähler aufgebraucht, bitte neu koppeln"
        "E_NO_SUCH_CONTACT" -> "Kontakt ist nicht auf der Antenne"
        "E_NO_SUCH_GROUP" -> "Gruppe ist nicht auf der Antenne"
        "E_RADIO" -> "Funkmodul der Antenne nicht bereit"
        "E_INVALID_ARG" -> "Die Antenne hat die Nachricht abgelehnt"
        else -> reason
    }

    fun rows(m: MessageEntity, recipient: String? = null): List<Pair<String, String>> = buildList {
        add("Status" to status(m, recipient))
        reason(m.failReason)?.takeIf { m.status == MessageStatus.FAILED }?.let { add("Grund" to it) }
        m.hops?.let { add("Hops" to hops(it)) }
        if (m.direction == "in") {
            m.rssi?.let { add("RSSI" to "$it dBm") }
            m.snr?.let { add("SNR" to String.format(Formats.LOCALE, "%.1f dB", it)) }
            m.retry?.let { add("Empfangene Sendung" to if (it == 0) "Erste Sendung" else "Wiederholung $it") }
        } else {
            m.retry?.let { add("Wiederholungen" to it.toString()) }
            m.rttMs?.let { add("Zeit bis Bestätigung" to duration(it)) }
            m.bytesOnAir?.let { add("Bytes auf Funk" to "$it B") }
            m.airtimeMs?.let { add("Sendezeit pro Versuch" to "≈ $it ms") }
        }
        m.compressed?.let { add("Komprimiert (OGZ1)" to if (it) "Ja" else "Nein") }
        m.ctr?.let { add("Zähler" to it.toString()) }
    }

    /** True when the antenna reported anything beyond the status (team firmware without delivery details). */
    fun hasRadioDetails(m: MessageEntity): Boolean =
        listOf(m.hops, m.rssi, m.snr, m.retry, m.rttMs, m.compressed, m.bytesOnAir).any { it != null }

    private fun hops(n: Int) = when (n) {
        0 -> "Direkt"
        1 -> "1 Weiterleitung"
        else -> "$n Weiterleitungen"
    }

    private fun duration(ms: Int) = if (ms < 1_000) "$ms ms" else String.format(Formats.LOCALE, "%.1f s", ms / 1000.0)
}
