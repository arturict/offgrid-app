package ch.offgrid.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import ch.offgrid.android.domain.AntennaActivity
import ch.offgrid.android.domain.AntennaError
import ch.offgrid.android.domain.AntennaManager
import ch.offgrid.android.domain.Conversation
import ch.offgrid.android.domain.KeyPattern
import ch.offgrid.android.domain.LocationError
import ch.offgrid.android.domain.PairingError
import ch.offgrid.android.domain.ResetResult
import ch.offgrid.android.ui.LocalApp
import ch.offgrid.android.ui.theme.Og
import kotlinx.coroutines.delay

/** Page frame: graphite background, safe-area padding, keyboard aware. */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().background(Og.bg).windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
        content = content,
    )
}

/** Current time that ticks every 30 s, so "zuletzt …" and signal staleness stay current. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Whole seconds until [at] (phone time), ticking every second; null when [at] is null. */
@Composable
fun rememberSecondsUntil(at: Long?): Long? {
    if (at == null) return null
    var left by remember(at) { mutableLongStateOf(((at - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)) }
    LaunchedEffect(at) {
        while (left > 0) {
            delay(1_000)
            left = ((at - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
        }
    }
    return left
}

fun patternFor(fingerprint: String): BooleanArray = KeyPattern.fromSeed(KeyPattern.contactSeed(fingerprint))

/**
 * Texts from the screen spec table "Fehlertexte". [code] is the link error code behind [AntennaError.REJECTED],
 * [retryInS] the estimated seconds left of a rate limit, [vaultStatus] what `sys.status` says about the vault.
 */
fun AntennaError.text(code: String? = null, retryInS: Long? = null, vaultStatus: String? = null): String = when (this) {
    AntennaError.NO_DEVICE -> "Keine Antenne gefunden. Prüfe das USB-C-Kabel."
    AntennaError.PERMISSION_DENIED -> "Zugriff auf die Antenne wurde nicht erlaubt."
    AntennaError.OPEN_FAILED -> "Die Antenne liess sich nicht öffnen. Steck sie neu ein."
    AntennaError.NO_INFO -> "Die Antenne antwortet nicht."
    AntennaError.PROTO_MISMATCH -> "Die Firmware der Antenne passt nicht zu dieser App-Version."
    AntennaError.LOST -> "Verbindung zur Antenne verloren. Ist das Kabel noch eingesteckt?"
    AntennaError.UNLOCK_FAILED -> "Diese Antenne ist mit einem anderen Handy verbunden. Übertrage sie mit „Daten exportieren“ vom alten Handy."
    AntennaError.RATE_LIMITED -> "Zu viele fehlgeschlagene Entsperrversuche. Die Antenne lässt erst nach einer Wartezeit wieder einen Versuch zu " +
        "(bis zu einer Minute)." + when {
            retryInS == null -> ""
            retryInS > 0 -> " Neuer Versuch in $retryInS s."
            else -> " Neuer Versuch läuft …"
        }
    AntennaError.NO_SESSION -> "Die Anmeldung bei der Antenne ist abgelaufen. Tippe auf „Erneut suchen“."
    AntennaError.VAULT_ERROR -> "Die Antenne kann ihren Schlüsselspeicher nicht lesen" + (vaultStatus?.let { " ($it)" } ?: "") +
        ". Steck sie neu ein. Hilft das nicht, muss sie zurückgesetzt werden."
    AntennaError.MOCK_FIRMWARE -> "Auf der Antenne läuft eine Test-Firmware ohne echte Verschlüsselung. Spiel eine aktuelle Firmware auf."
    AntennaError.UNRESPONSIVE -> "Die Antenne antwortet nicht mehr. Steck sie neu ein."
    AntennaError.REJECTED -> "Die Antenne hat die Verbindung abgelehnt" + (code?.let { " ($it)." } ?: ".")
}

/** Why the antenna locked itself (`sec.state` reason, or a restart), for the connect screen and the antenna info. */
fun lockReasonText(reason: String): String = when (reason) {
    "heartbeat_timeout" -> "Die Antenne hat sich gesperrt, weil sie zu lange nichts vom Handy gehört hat."
    "lock" -> "Die Antenne wurde gesperrt."
    "factory_reset" -> "Die Antenne wird zurückgesetzt und danach für dieses Handy neu eingerichtet."
    "error" -> "Die Antenne hat sich wegen eines internen Fehlers gesperrt."
    AntennaManager.REASON_RESTART -> "Die Antenne wurde neu gestartet und war danach gesperrt."
    else -> "Die Antenne ist gesperrt."
}

/** Short form of [lockReasonText] for an info row. */
fun lockReasonShort(reason: String): String = when (reason) {
    "heartbeat_timeout" -> "kein Lebenszeichen vom Handy"
    "lock" -> "gesperrt"
    "factory_reset" -> "Zurücksetzen"
    "error" -> "interner Fehler"
    AntennaManager.REASON_RESTART -> "Neustart"
    else -> reason
}

/** Meta of the step "Verbindung wird aufgebaut" while the session works on the antenna. */
fun AntennaActivity.text(): String = when (this) {
    AntennaActivity.HELLO -> "Antenne startet …"
    AntennaActivity.PROVISIONING -> "Neue Antenne wird eingerichtet"
    AntennaActivity.UNLOCKING -> "Wird entsperrt"
    AntennaActivity.RELOCKING -> "Wird wieder entsperrt"
    AntennaActivity.RESETTING -> "Wird zurückgesetzt"
}

/** Why the antenna reset ("Alle Daten löschen", "Antenne zurücksetzen") stopped or needs a note; null when it went through. */
fun ResetResult.text(): String? = when (this) {
    ResetResult.DONE, ResetResult.NOT_CONNECTED -> null
    ResetResult.DONE_NOT_RECONNECTED ->
        "Die Antenne hat das Zurücksetzen angenommen, meldet sich aber noch nicht wieder. Warte kurz oder steck sie neu ein und tippe auf „Neu verbinden“. " +
            "Ihre Kontakte müssen neu gekoppelt werden; deine Chats sind noch da."
    ResetResult.WRONG_PHONE -> "Die Antenne gehört zu einem anderen Handy und wurde nicht zurückgesetzt. Auf diesem Handy wurde nichts gelöscht."
    ResetResult.RATE_LIMITED -> "Zu viele fehlgeschlagene Versuche. Die Antenne wartet bis zu einer Minute. Versuch es dann erneut. Es wurde nichts gelöscht."
    ResetResult.UNSUPPORTED ->
        "Diese Antennen-Firmware kennt das Zurücksetzen per App nicht. Einen Knopf dafür gibt es nicht; spiel eine aktuelle Firmware auf. Es wurde nichts gelöscht."
    ResetResult.FAILED -> "Die Antenne konnte nicht zurückgesetzt werden. Versuch es erneut. Es wurde nichts gelöscht."
    ResetResult.UNKNOWN ->
        "Die Verbindung zur Antenne brach während des Zurücksetzens ab. Ob die Antenne zurückgesetzt wurde, ist unklar. " +
            "Steck sie neu ein und verbinde sie neu. Auf diesem Handy wurde nichts gelöscht oder geändert."
}

fun PairingError.text(): String = when (this) {
    PairingError.INVALID_QR -> "Das ist kein OffGrid-Code."
    PairingError.ANTENNA_NOT_READY -> "Antenne nicht verbunden. Der Schlüssel wird gesendet, sobald sie verbunden ist."
    PairingError.CONTACT_EXISTS -> "Dieser Kontakt ist schon gespeichert. „Neu koppeln“ ersetzt eure Schlüssel; dein Gegenüber muss dich dann auch neu scannen."
    PairingError.CODE_USED -> "Diesen Code hat die Antenne schon verwendet. Dein Gegenüber soll „Kontakt hinzufügen“ neu öffnen, dann scannst du den neuen Code."
    PairingError.SELF_PAIR -> "Das ist dein eigener Code."
    PairingError.FAILED -> "Der Schlüsselaustausch hat nicht geklappt. Versucht es erneut."
}

fun LocationError.text(): String = when (this) {
    LocationError.DENIED -> "Standortzugriff fehlt. Erlaube ihn in den Android-Einstellungen."
    LocationError.TIMEOUT -> "Kein GPS-Signal. Geh ins Freie und versuch es erneut."
    LocationError.UNAVAILABLE -> "Der Standort ist gerade nicht verfügbar."
    LocationError.NO_MAPS_APP -> "Keine Karten-App gefunden. Die Koordinaten sind kopiert."
}

/**
 * Marks [conversation] as on screen while this screen is resumed: its notifications are cleared
 * and new ones are not posted.
 */
@Composable
fun ConversationOnScreen(conversation: Conversation) {
    val notifier = LocalApp.current.notifier
    LifecycleResumeEffect(conversation) {
        notifier.visible = conversation
        notifier.cancel(conversation)
        onPauseOrDispose { if (notifier.visible == conversation) notifier.visible = null }
    }
}
