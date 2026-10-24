package ch.offgrid.android.ui.screens

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import ch.offgrid.android.BuildConfig
import ch.offgrid.android.DemoData
import ch.offgrid.android.link.LinkKind
import ch.offgrid.android.ui.LocalApp
import ch.offgrid.android.ui.Routes
import ch.offgrid.android.ui.back
import ch.offgrid.android.ui.components.AvatarVariant
import ch.offgrid.android.ui.components.ButtonVariant
import ch.offgrid.android.ui.components.CountdownPill
import ch.offgrid.android.ui.components.Footer
import ch.offgrid.android.ui.components.MutualScanIllustration
import ch.offgrid.android.ui.components.OgAvatar
import ch.offgrid.android.ui.components.OgButton
import ch.offgrid.android.ui.components.OgCard
import ch.offgrid.android.ui.components.OgIcon
import ch.offgrid.android.ui.components.OgIconView
import ch.offgrid.android.ui.components.OgNote
import ch.offgrid.android.ui.components.OgTextField
import ch.offgrid.android.ui.components.OgTopBar
import ch.offgrid.android.ui.components.QrCard
import ch.offgrid.android.ui.components.QrScanner
import ch.offgrid.android.ui.components.Step
import ch.offgrid.android.ui.components.StepList
import ch.offgrid.android.ui.components.StepState
import ch.offgrid.android.ui.components.WizardProgress
import ch.offgrid.android.ui.theme.LocalReducedMotion
import ch.offgrid.android.ui.theme.Og
import ch.offgrid.android.ui.theme.OgType
import kotlinx.coroutines.launch

/** Texts under the QR code and the scanner: 16 px, line height 1.45, `--og-text-2` (`Handshake.dc.html`). */
private val IntroStyle = OgType.body2.copy(fontSize = 16.sp, lineHeight = 1.45.em, textAlign = TextAlign.Center)

/** Screen 4 "Kontakt hinzufügen" as a guided mutual QR scan; the logic is in [PairingWizard]. */
@Composable
fun AddContactScreen(nav: NavHostController, scanTab: Boolean) {
    val app = LocalApp.current
    val vm = viewModel {
        // App scope: removing a half-paired contact must finish after the screen is gone.
        AddContactViewModel(app.pairing, app.antenna.state, { id -> app.scope.launch { app.messaging.deleteContact(id) } }, scanTab)
    }
    val wizard = vm.wizard
    val ui by wizard.ui.collectAsStateWithLifecycle()
    val linkKind by app.linkKind.collectAsStateWithLifecycle()
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current

    val step = ui.step
    LaunchedEffect(step) {
        if (step is WizardStep.Success) nav.navigate(Routes.added(step.contactId)) { popUpTo(Routes.ADD) { inclusive = true } }
    }
    val close = {
        confirmCancel = false
        wizard.cancel()
        nav.back()
    }
    val requestClose = { if (ui.cancelNeedsConfirm) confirmCancel = true else close() }
    BackHandler(onBack = requestClose)

    Page {
        OgTopBar("Kontakt hinzufügen", onBack = requestClose, backLabel = "Kopplung abbrechen")
        WizardProgress(
            // Expired is not "finished": no bar is filled.
            if (step == WizardStep.Expired) 0 else ui.stepNumber, WIZARD_STEPS, title = stepTitle(step),
            label = if (step == WizardStep.Expired) "Zeit abgelaufen" else null,
        )
        BoxWithConstraints(Modifier.weight(1f)) {
            // At least as tall as the viewport, so the hint sits at the bottom like in the design and
            // scrolls with the rest when large font sizes make the page longer than the screen.
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (reduced) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            (fadeIn(tween(220, delayMillis = 60)) + slideInHorizontally(tween(260)) { it / 12 }) togetherWith fadeOut(tween(120))
                        }
                    },
                    label = "wizardStep",
                ) { shown ->
                    Column(Modifier.fillMaxWidth()) {
                        when (shown) {
                            WizardStep.Intro -> IntroStep()
                            is WizardStep.Half -> if (shown.role == PairRole.SHOW) {
                                ShowStep(ui, shown.second)
                            } else {
                                ScanStep(ui, simulated = linkKind == LinkKind.SIMULATED, wizard)
                            }
                            WizardStep.Confirming -> ConfirmingStep(ui)
                            WizardStep.Expired -> ExpiredStep()
                            is WizardStep.Success -> Unit
                        }
                    }
                }
                Problem(ui, wizard, onConnect = { nav.navigate(Routes.CONNECT) })
                Spacer(Modifier.height(16.dp))
                Spacer(Modifier.weight(1f))
                if (step is WizardStep.Half && step.role == PairRole.SHOW) {
                    Text(
                        "Der Code enthält deinen Schlüssel. Zeig ihn nur Personen, mit denen du schreiben willst.",
                        style = OgType.help.copy(lineHeight = 1.45.em, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
                    )
                }
            }
        }
        StepActions(step, wizard)
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            containerColor = Og.surface,
            shape = RoundedCornerShape(Og.rCard),
            title = { Text("Kopplung abbrechen?", style = OgType.bodyStrong) },
            text = {
                Text(
                    "Ihr müsst danach beide neu beginnen." +
                        (ui.cancelRemoves?.let { " Der unvollständige Kontakt $it wird wieder entfernt." } ?: ""),
                    style = OgType.body,
                )
            },
            confirmButton = { TextButton(onClick = close) { Text("Abbrechen", style = OgType.bodyStrong.copy(color = Og.danger)) } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Weiter koppeln", style = OgType.bodyStrong) } },
        )
    }
}

private fun stepTitle(step: WizardStep): String = when (step) {
    WizardStep.Intro -> "So koppelt ihr eure Handys"
    is WizardStep.Half -> when {
        step.role == PairRole.SHOW && !step.second -> "Zeig deinen Code"
        step.role == PairRole.SCAN && !step.second -> "Scanne den Code deines Gegenübers"
        step.role == PairRole.SHOW -> "Jetzt zeigst du deinen Code"
        else -> "Jetzt scannst du den Code deines Gegenübers"
    }
    WizardStep.Confirming -> "Fast geschafft"
    WizardStep.Expired -> "Der Code ist abgelaufen"
    is WizardStep.Success -> "Gekoppelt"
}

@Composable
private fun IntroStep() {
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, top = 28.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        MutualScanIllustration()
        Text(
            "Ihr scannt euch gegenseitig: zuerst scannt einer, dann der andere.",
            style = IntroStyle.copy(color = Og.text),
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
    StepList(
        spinner = false,
        steps = listOf(
            Step("Einer zeigt seinen Code, der andere scannt", StepState.Todo, meta = "1"),
            Step("Dann tauscht ihr die Rollen", StepState.Todo, meta = "2"),
            Step("Die Antennen bestätigen sich per Funk", StepState.Todo, meta = "3"),
        ),
    )
    Text(
        "Öffnet auf beiden Handys „Kontakt hinzufügen“. Wer anfängt, ist egal; wählt nur verschiedene Rollen.",
        style = OgType.help.copy(lineHeight = 1.45.em, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, top = 16.dp, end = 28.dp),
    )
}

@Composable
private fun ShowStep(ui: WizardUi, second: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, top = 22.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        QrCard(ui.qr)
        ui.secondsLeft?.let { CountdownPill(it) }
        val peer = ui.peerName
        Text(
            if (second && peer != null) {
                "Jetzt scannt $peer deinen Code. Sobald die Antenne antwortet, geht es von selbst weiter."
            } else {
                "Lass dein Gegenüber diesen Code mit der OffGrid-App scannen."
            },
            style = IntroStyle,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
    if (ui.codeRenewed) {
        OgNote("Dein Code hat sich erneuert. ${ui.peerName ?: "Dein Gegenüber"} muss ihn nochmals scannen.")
    }
    ui.waiting?.let { StepList(spinner = false, steps = it) }
}

@Composable
private fun ConfirmingStep(ui: WizardUi) {
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, top = 28.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OgAvatar(null, 96.dp, AvatarVariant.Pending)
        ui.peerName?.let { Text(it, style = OgType.hero.copy(textAlign = TextAlign.Center)) }
        Text(
            "Die Antennen bestätigen sich per Funk. Das dauert meist nur ein paar Sekunden.",
            style = IntroStyle,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
    ui.waiting?.let { StepList(spinner = false, steps = it) }
}

@Composable
private fun ExpiredStep() {
    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, top = 40.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(Og.dangerBg).border(1.dp, Og.dangerBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) { OgIconView(OgIcon.Clock, Og.dangerTitle, size = 40.dp) }
        Text(
            "Ein Code gilt 5 Minuten. Tippt auf beiden Handys auf „Neu starten“ und scannt euch dann nochmals gegenseitig.",
            style = IntroStyle,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}

/** The current problem as a note, with the button that solves it where there is one. */
@Composable
private fun Problem(ui: WizardUi, wizard: PairingWizard, onConnect: () -> Unit) {
    val problem = ui.problem ?: return
    if (ui.step is WizardStep.Success || ui.step == WizardStep.Expired) return
    val context = LocalContext.current
    OgNote(problem.text)
    val action: Pair<String, () -> Unit>? = when {
        ui.canReplace -> "Neu koppeln" to wizard::replace
        problem == WizardProblem.CAMERA_DENIED -> "Android-Einstellungen öffnen" to { context.openAppSettings() }
        problem == WizardProblem.ANTENNA_DISCONNECTED || problem == WizardProblem.ANTENNA_LOCKED -> "Antenne verbinden" to onConnect
        else -> null
    }
    action?.let { (label, onClick) ->
        OgButton(label, onClick, Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp), variant = ButtonVariant.Secondary)
    }
}

/** The buttons that move the wizard on; fixed at the bottom. */
@Composable
private fun StepActions(step: WizardStep, wizard: PairingWizard) {
    when (step) {
        WizardStep.Intro -> Footer {
            OgButton("Ich zeige meinen Code", { wizard.choose(PairRole.SHOW) }, icon = OgIcon.Qr)
            OgButton("Ich scanne", { wizard.choose(PairRole.SCAN) }, variant = ButtonVariant.Secondary)
        }
        is WizardStep.Half -> when {
            step.role == PairRole.SHOW && !step.second -> Footer {
                OgButton("Mein Gegenüber hat gescannt", wizard::peerScanned, icon = OgIcon.Check)
                OgButton("Lieber zuerst scannen", { wizard.choose(PairRole.SCAN) }, variant = ButtonVariant.Secondary)
            }
            step.role == PairRole.SCAN && !step.second -> Footer {
                OgButton("Lieber zuerst meinen Code zeigen", { wizard.choose(PairRole.SHOW) }, variant = ButtonVariant.Secondary)
            }
            step.role == PairRole.SCAN -> Footer {
                OgButton("Mein Code wurde noch nicht gescannt", wizard::peerNotScannedYet, variant = ButtonVariant.Secondary)
            }
            else -> Unit
        }
        WizardStep.Confirming -> Footer {
            OgButton("Meinen Code nochmals zeigen", wizard::showCodeAgain, variant = ButtonVariant.Secondary)
        }
        WizardStep.Expired -> Footer {
            OgButton("Neu starten", wizard::restart, icon = OgIcon.Refresh)
        }
        is WizardStep.Success -> Unit
    }
}

/** The camera opens as soon as the step is shown; after a result it waits for a tap to scan again. */
@Composable
private fun ScanStep(ui: WizardUi, simulated: Boolean, wizard: PairingWizard) {
    val context = LocalContext.current
    var cameraOn by rememberSaveable { mutableStateOf(false) }
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraOn = true else wizard.cameraProblem(CameraProblem.DENIED)
    }
    val start = {
        wizard.clearError()
        when {
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) -> wizard.cameraProblem(CameraProblem.NO_CAMERA)
            context.hasCameraPermission() -> cameraOn = true
            else -> permission.launch(Manifest.permission.CAMERA)
        }
    }
    LaunchedEffect(Unit) {
        if (!autoStarted) {
            autoStarted = true
            start()
        }
    }
    // Back from the Android settings with the camera allowed: the hint is no longer true.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (ui.problem == WizardProblem.CAMERA_DENIED && context.hasCameraPermission()) {
            wizard.cameraProblem(null)
            cameraOn = true
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(start = 24.dp, top = 22.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val shape = RoundedCornerShape(Og.rQr)
        Box(
            // 298 as in the design; narrower screens shrink it to a square that still fits.
            Modifier.widthIn(max = 298.dp).fillMaxWidth().aspectRatio(1f)
                .clip(shape).background(Og.bg).border(1.dp, Og.border, shape)
                .then(
                    if (cameraOn) {
                        Modifier.semantics { contentDescription = "Kamera läuft" }
                    } else {
                        Modifier.clickable(role = Role.Button, onClick = start).semantics { contentDescription = "Kamera starten und Code scannen" }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (cameraOn) {
                QrScanner(onResult = { cameraOn = false; wizard.scan(it) }, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    "KAMERA STARTEN",
                    style = OgType.meta.copy(letterSpacing = 0.1.em),
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            ScanCorners(Modifier.matchParentSize())
        }
        ui.secondsLeft?.let { CountdownPill(it) }
        Text("Halte die Kamera auf den Code, den dein Gegenüber anzeigt.", style = IntroStyle, modifier = Modifier.widthIn(max = 290.dp))
    }
    if (BuildConfig.DEBUG) DebugCodeInput(simulated, wizard::scan)
}

/** Debug builds only: paste a QR text (from a chat or the demo menu) or scan a simulated peer. */
@Composable
private fun DebugCodeInput(simulated: Boolean, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var pasting by rememberSaveable { mutableStateOf(false) }
    var pasted by rememberSaveable { mutableStateOf("") }
    if (pasting) {
        OgCard(padded = true, modifier = Modifier.padding(top = 16.dp)) {
            OgTextField("Code einfügen", pasted, { pasted = it.trim() }, placeholder = "T0cx…")
            OgButton("Code verwenden", { pasting = false; onCode(pasted) }, enabled = pasted.isNotEmpty())
        }
        return
    }
    Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OgButton("Code aus Zwischenablage einfügen", {
            val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            pasted = clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
            pasting = true
        }, variant = ButtonVariant.Secondary)
        if (simulated) {
            // A fresh virtual peer on the simulated antenna, scanned as if from its screen.
            OgButton("Simulierten Kontakt scannen", {
                scope.launch { onCode(DemoData(app).newPeerCode(DemoData.nextPeerName())) }
            }, variant = ButtonVariant.Secondary)
        }
    }
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Once Android stops showing the permission dialog, the app's settings page is the only way back. */
private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Four accent corner brackets as the CSS draws them: 46x46 boxes 34 from the edge with 4 wide
 * left/top borders (square ends) and outer radius 14. The stroke runs on the centre line of that
 * border, so it sits 2 inside the outer edge with radius 12.
 */
@Composable
private fun ScanCorners(modifier: Modifier) {
    Canvas(modifier) {
        val width = 4.dp.toPx()
        val line = 34.dp.toPx() + width / 2
        val end = (34 + 46).dp.toPx()
        val r = 14.dp.toPx() - width / 2
        val topLeft = Path().apply {
            moveTo(line, end)
            lineTo(line, line + r)
            arcTo(Rect(line, line, line + 2 * r, line + 2 * r), 180f, 90f, false)
            lineTo(end, line)
        }
        val stroke = Stroke(width, cap = StrokeCap.Butt, join = StrokeJoin.Miter)
        // The other three corners are mirror images of the top-left one.
        listOf(1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f).forEach { (sx, sy) ->
            scale(sx, sy, pivot = center) { drawPath(topLeft, Og.accent, style = stroke) }
        }
    }
}
