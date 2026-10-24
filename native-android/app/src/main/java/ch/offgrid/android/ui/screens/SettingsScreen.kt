package ch.offgrid.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ch.offgrid.android.BuildConfig
import ch.offgrid.android.domain.AntennaActivity
import ch.offgrid.android.domain.Formats
import ch.offgrid.android.domain.KeyPattern
import ch.offgrid.android.domain.MessageLimits
import ch.offgrid.android.domain.ResetResult
import ch.offgrid.android.domain.Settings
import ch.offgrid.android.link.LinkKind
import ch.offgrid.android.ui.LocalApp
import ch.offgrid.android.ui.Routes
import ch.offgrid.android.ui.back
import ch.offgrid.android.ui.components.ButtonVariant
import ch.offgrid.android.ui.components.CardDivider
import ch.offgrid.android.ui.components.InfoRow
import ch.offgrid.android.ui.components.OgAvatar
import ch.offgrid.android.ui.components.OgButton
import ch.offgrid.android.ui.components.OgCard
import ch.offgrid.android.ui.components.OgIcon
import ch.offgrid.android.ui.components.OgIconView
import ch.offgrid.android.ui.components.OgNote
import ch.offgrid.android.ui.components.OgSwitch
import ch.offgrid.android.ui.components.OgTextField
import ch.offgrid.android.ui.components.OgTopBar
import ch.offgrid.android.ui.components.SectionLabel
import ch.offgrid.android.ui.components.SignalBars
import ch.offgrid.android.ui.components.Spinner
import ch.offgrid.android.ui.components.StatusDot
import ch.offgrid.android.ui.theme.Grotesk
import ch.offgrid.android.ui.theme.Og
import ch.offgrid.android.ui.theme.OgType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Screen 7 "Einstellungen". */
@Composable
fun SettingsScreen(nav: NavHostController) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // Receiving stays on whatever the answer is; without the permission Android only hides the notification.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val antenna by app.antenna.state.collectAsStateWithLifecycle()
    val settings by app.settings.values.collectAsStateWithLifecycle()
    val contacts by app.messaging.contacts.collectAsStateWithLifecycle(initialValue = emptyList())
    val messageCount by app.messaging.messageCount.collectAsStateWithLifecycle(initialValue = 0)
    val installId by produceState<String?>(null) { value = app.settings.installId() }
    var name by rememberSaveable { mutableStateOf<String?>(null) }
    var nameError by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    // Antenna reset: 0 = idle, 1 = consequences, 2 = last question; then it runs and ends in a note.
    var resetStep by rememberSaveable { mutableStateOf(0) }
    var resetting by remember { mutableStateOf(false) }
    var resetError by remember { mutableStateOf<String?>(null) }
    var resetDone by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings[Settings.DISPLAY_NAME]) { if (name == null) name = settings[Settings.DISPLAY_NAME] }
    val pattern = remember(settings[Settings.OWN_PUBLIC_KEY], installId) {
        KeyPattern.fromSeed(settings[Settings.OWN_PUBLIC_KEY] ?: installId ?: "")
    }
    // Saves in the app scope: leaving the screen while the field still has focus cancels this
    // screen's scope, and the edit would be lost.
    fun saveName() {
        val value = name?.trim() ?: return
        if (!MessageLimits.isValidName(value)) {
            nameError = true
        } else if (value != settings[Settings.DISPLAY_NAME]) {
            app.scope.launch {
                app.settings.put(Settings.DISPLAY_NAME, value)
                runCatching { app.antenna.call("id.set_name", buildJsonObject { put("name", value) }) }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { saveName() } }
    val now = rememberNow()
    val bestLevel = contacts.filter { it.state == "active" }.maxOfOrNull { Formats.signalLevel(it.lastRssi, it.lastSeenAt, now) } ?: 0

    Page {
        OgTopBar("Einstellungen", onBack = { nav.back() }, backLabel = "Zurück zu den Kontakten")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OgAvatar(pattern, 72.dp)
                Text("DEIN MUSTER", style = OgType.meta.copy(letterSpacing = 0.08.em))
            }

            SectionLabel("Profil", top = 22.dp)
            OgCard(padded = true, gap = 8.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                OgTextField(
                    "Anzeigename", name.orEmpty(), {
                        name = MessageLimits.clampToBytes(it, MessageLimits.MAX_NAME_BYTES)
                        nameError = false
                    },
                    hint = "Wird beim Hinzufügen an neue Kontakte übertragen.",
                    gap = 8.dp,
                    error = if (nameError) NAME_ERROR else null,
                    onBlur = ::saveName,
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                    onImeAction = { focusManager.clearFocus() },
                )
            }

            SectionLabel("Antenne")
            OgCard {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusDot(antenna.ready)
                    Text(if (antenna.ready) "Verbunden" else "Getrennt", style = OgType.body, modifier = Modifier.weight(1f))
                    val level = if (antenna.ready) bestLevel else 0
                    SignalBars(level, label = Formats.signalLabel(level))
                }
                CardDivider()
                InfoRow("Anschluss", if (antenna.kind == LinkKind.SIMULATED) "Simuliert" else "USB-C · Serial")
                InfoRow("Frequenz", antenna.freqMhz?.let { "${Formats.band(it)} MHz" } ?: "–")
                InfoRow("Firmware", antenna.fwVersion ?: "–")
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OgButton("Neu verbinden", { nav.navigate(Routes.CONNECT) }, Modifier.weight(1f),
                        variant = ButtonVariant.Secondary, containerColor = Og.line, height = 46.dp, radius = 14.dp, bordered = false)
                    OgButton("Trennen", { app.antenna.disconnect() }, Modifier.weight(1f),
                        variant = ButtonVariant.Secondary, enabled = antenna.ready, height = 46.dp, radius = 14.dp)
                }
                CardDivider()
                OgButton("Antenne auf Werkseinstellungen zurücksetzen", { resetError = null; resetDone = null; resetStep = 1 },
                    variant = ButtonVariant.DangerText, enabled = antenna.ready && !resetting)
                if (!antenna.ready) {
                    Text("Verbinde zuerst die Antenne, um sie zurückzusetzen.", style = OgType.meta,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
                }
            }
            resetError?.let { OgNote(it) }
            resetDone?.let { Text(it, style = OgType.body, modifier = Modifier.padding(start = 28.dp, top = 14.dp, end = 28.dp)) }

            SectionLabel("Empfang")
            OgCard {
                SwitchRow(
                    "Im Hintergrund empfangen",
                    "Nachrichten kommen an, auch wenn die App geschlossen ist.",
                    settings[Settings.BG_RECEIVE] != "false",
                ) { on ->
                    scope.launch { app.settings.put(Settings.BG_RECEIVE, on.toString()) }
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                CardDivider()
                SwitchRow("Vibration bei Nachricht", null, settings[Settings.VIBRATE] == "true") { on ->
                    scope.launch { app.settings.put(Settings.VIBRATE, on.toString()) }
                }
            }

            SectionLabel("Erweitert")
            OgCard {
                // Lab boards always show it; for the team firmware it is behind a switch, most people never need it.
                if (!antenna.labRadio) {
                    SwitchRow("Erweiterte Einstellungen", "Firmware, Funk, Mesh und Statistik der Antenne.",
                        settings[Settings.SHOW_ADVANCED] == "true") { on ->
                        scope.launch { app.settings.put(Settings.SHOW_ADVANCED, on.toString()) }
                    }
                }
                if (antenna.labRadio || settings[Settings.SHOW_ADVANCED] == "true") {
                    if (!antenna.labRadio) CardDivider()
                    LinkRow(OgIcon.Antenna, if (antenna.labRadio) "Erweitert / Lab" else "Erweitert",
                        "Firmware, Funk, Mesh${if (antenna.labRadio) ", Ignorierliste, Codec" else ""} und Funkstatistik.") {
                        nav.navigate(Routes.ADVANCED)
                    }
                }
            }

            SectionLabel("Handywechsel")
            OgCard {
                LinkRow(OgIcon.Upload, "Daten exportieren", "Kontakte, Schlüssel und Nachrichten als Datei, mit Passwort geschützt.") {
                    nav.navigate(Routes.EXPORT)
                }
                CardDivider()
                LinkRow(OgIcon.Download, "Daten importieren", "Export-Datei vom alten Handy einlesen.") { nav.navigate(Routes.IMPORT) }
            }

            SectionLabel("Daten")
            OgCard {
                InfoRow("Kontakte", contacts.size.toString())
                InfoRow("Nachrichten", messageCount.toString())
                OgButton(if (deleting) "Wird gelöscht …" else "Alle Daten löschen", { deleteError = null; confirmDelete = true },
                    variant = ButtonVariant.DangerText, enabled = !deleting)
            }
            deleteError?.let { OgNote(it) }
            Text(
                "OffGrid ${BuildConfig.VERSION_NAME}",
                style = OgType.meta,
                modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 28.dp, end = 28.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    if (resetStep == 1) {
        val mine = contacts.count { it.deviceId == antenna.deviceId }
        AlertDialog(
            onDismissRequest = { resetStep = 0 },
            containerColor = Og.surface,
            shape = RoundedCornerShape(Og.rCard),
            title = { Text("Antenne zurücksetzen?", style = OgType.bodyStrong) },
            text = {
                Text(
                    "Die Antenne wird auf Werkseinstellungen zurückgesetzt.\n\n" +
                        "• Ihre Identität und alle Schlüssel werden gelöscht. Sie bekommt eine neue Antennen-ID.\n" +
                        "• " + (if (mine == 1) "Dein Kontakt muss" else if (mine > 1) "Deine $mine Kontakte müssen" else "Neue Kontakte müssen") +
                        " neu gekoppelt werden: Ihr scannt eure Codes erneut. Bis dahin kannst du nicht schreiben.\n" +
                        "• Deine Chats bleiben auf diesem Handy und bleiben lesbar. Nachrichten, die noch unterwegs waren, gelten als nicht zugestellt.\n" +
                        "• Die Antenne startet neu und wird für dieses Handy eingerichtet. Das dauert einige Sekunden; zieh das Kabel nicht ab.\n\n" +
                        "Das lässt sich nicht rückgängig machen.",
                    style = OgType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = { resetStep = 2 }) {
                    Text("Weiter", style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(700), color = Og.danger))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetStep = 0 }) {
                    Text("Abbrechen", style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(600), color = Og.text))
                }
            },
        )
    }

    if (resetStep == 2) {
        AlertDialog(
            onDismissRequest = { resetStep = 0 },
            containerColor = Og.surface,
            shape = RoundedCornerShape(Og.rCard),
            title = { Text("Wirklich zurücksetzen?", style = OgType.bodyStrong) },
            text = {
                Text(
                    "Das ist die letzte Rückfrage. Die Schlüssel der Antenne sind danach für immer weg, und alle Kontakte müssen neu gekoppelt werden.",
                    style = OgType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    resetStep = 0
                    resetting = true
                    // App scope: the reset must not be cut off when this screen goes away.
                    app.scope.launch {
                        val result = app.messaging.resetAntenna()
                        scope.launch {
                            resetting = false
                            if (result == ResetResult.DONE) {
                                resetDone = "Die Antenne wurde zurückgesetzt und ist bereit. Koppelt eure Kontakte neu, um wieder zu schreiben."
                            } else {
                                resetError = result.text()
                            }
                        }
                    }
                }) { Text("Endgültig zurücksetzen", style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(700), color = Og.danger)) }
            },
            dismissButton = {
                TextButton(onClick = { resetStep = 0 }) {
                    Text("Abbrechen", style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(600), color = Og.text))
                }
            },
        )
    }

    if (resetting) {
        // Reset and restart take several seconds; the dialog cannot be dismissed so nobody pulls the cable early.
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            containerColor = Og.surface,
            shape = RoundedCornerShape(Og.rCard),
            confirmButton = {},
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(28.dp)
                    Text(
                        if (antenna.activity == AntennaActivity.PROVISIONING) "Antenne wird für dieses Handy eingerichtet …"
                        else "Antenne wird zurückgesetzt. Zieh das Kabel nicht ab …",
                        style = OgType.body,
                    )
                }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Og.surface,
            shape = RoundedCornerShape(Og.rCard),
            text = {
                Text(
                    if (antenna.ready) {
                        "Alle Kontakte und Nachrichten löschen und die Antenne auf Werkseinstellungen zurücksetzen? " +
                            "Danach müsst ihr euch neu koppeln. Das lässt sich nicht rückgängig machen."
                    } else {
                        "Alle Kontakte und Nachrichten auf diesem Handy löschen? Die Antenne ist nicht verbunden und " +
                            "behält ihre Kontakte; verbinde sie zuerst, um sie mitzulöschen. Das lässt sich nicht rückgängig machen."
                    },
                    style = OgType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    // App scope: the reset must not be cut off when this screen goes away.
                    app.scope.launch {
                        val result = app.messaging.deleteAll()
                        scope.launch {
                            deleting = false
                            deleteError = result.text()
                            if (deleteError == null) {
                                app.resetAnalyticsId()
                                nav.navigate(Routes.CONTACTS) { popUpTo(Routes.CONTACTS) { inclusive = true } }
                            }
                        }
                    }
                }) { Text("Löschen", style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(700), color = Og.danger)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Abbrechen", style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(600), color = Og.text))
                }
            },
        )
    }
}

@Composable
internal fun SwitchRow(title: String, help: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    // The whole row toggles, so TalkBack reads title, help and state as one switch.
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (help != null) 68.dp else 60.dp).alpha(if (enabled) 1f else 0.4f)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = OgType.body)
            if (help != null) Text(help, style = OgType.help.copy(fontSize = 13.sp))
        }
        OgSwitch(checked, onCheckedChange = null)
    }
}

@Composable
internal fun LinkRow(icon: OgIcon, title: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OgIconView(icon, Og.accent, size = 22.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = OgType.bodyStrong.copy(fontSize = 16.sp, fontWeight = FontWeight(600)))
            Text(text, style = OgType.help.copy(fontSize = 13.sp))
        }
    }
}
