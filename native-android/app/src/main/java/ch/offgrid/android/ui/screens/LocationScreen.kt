package ch.offgrid.android.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ch.offgrid.android.domain.Formats
import ch.offgrid.android.domain.Geo
import ch.offgrid.android.domain.Heading
import ch.offgrid.android.domain.LocationError
import ch.offgrid.android.ui.LocalApp
import ch.offgrid.android.ui.back
import ch.offgrid.android.ui.components.ButtonVariant
import ch.offgrid.android.ui.components.CardDivider
import ch.offgrid.android.ui.components.CompassDial
import ch.offgrid.android.ui.components.CompassState
import ch.offgrid.android.ui.components.Footer
import ch.offgrid.android.ui.components.InfoRow
import ch.offgrid.android.ui.components.OgButton
import ch.offgrid.android.ui.components.OgCard
import ch.offgrid.android.ui.components.OgIcon
import ch.offgrid.android.ui.components.OgIconButton
import ch.offgrid.android.ui.components.OgNote
import ch.offgrid.android.ui.components.OgTopBar
import ch.offgrid.android.ui.components.rememberBearingAngle
import ch.offgrid.android.ui.components.rememberCompass
import ch.offgrid.android.ui.components.rememberCompassAngle
import ch.offgrid.android.ui.components.rememberStickySector
import ch.offgrid.android.ui.theme.Grotesk
import ch.offgrid.android.ui.theme.Mono
import ch.offgrid.android.ui.theme.Og
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Screen 11 "Standort ansehen". For a received position the arrow points at it in the real world
 * (bearing minus the phone's heading) while the rose turns so its N points north; for my own position
 * the arrow on a north-up rose shows where the phone faces. Without a compass only the words remain.
 */
@Composable
fun LocationScreen(nav: NavHostController, contactId: Long, messageId: Long) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contact by remember(contactId) { app.messaging.contact(contactId).asLookup() }.collectAsStateWithLifecycle(Lookup.Loading)
    val message by remember(messageId) { app.messaging.message(messageId).asLookup() }.collectAsStateWithLifecycle(Lookup.Loading)
    val myFix by app.location.fix.collectAsStateWithLifecycle()
    val locating by app.location.locating.collectAsStateWithLifecycle()
    val locationError by app.location.error.collectAsStateWithLifecycle()
    val now = rememberNow()
    // Declination needs only a rough position, so any fix will do, however old.
    val compass = rememberCompass(myFix)
    var copied by remember { mutableStateOf(false) }
    var mapsError by rememberSaveable { mutableStateOf(false) }
    // Locate once per visit; a rotation must not ask for the permission again.
    var autoLocated by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { app.location.locate() }
    }
    fun locate() {
        if (app.location.needsPermission()) {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            scope.launch { app.location.locate() }
        }
    }
    LaunchedEffect(Unit) {
        if (!autoLocated) {
            autoLocated = true
            locate()
        }
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }
    // Spec §11: a missing message (deleted, or a stale id after process death) leads back to the chat.
    val m = message.found
    val c = contact.found
    val lat = m?.lat
    val lon = m?.lon
    LeaveWhenGone(message == Lookup.Gone || contact == Lookup.Gone || (m != null && (lat == null || lon == null)), nav)
    if (m == null || c == null || lat == null || lon == null) {
        Page {}
        return
    }
    val out = m.direction == "out"
    val fix = myFix?.takeIf { app.location.isFresh(it, now) }
    val bearing = fix?.let { Geo.bearingDegrees(it.latitude, it.longitude, lat, lon) }
    val recordedAt = m.sentAt - (m.ageS ?: 0) * 1000L

    Page {
        OgTopBar(if (out) "Mein Standort" else "Standort von ${c.displayName}", onBack = { nav.back() }, backLabel = "Zurück zum Chat")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (!out) {
                TargetCompass(compass, bearing, fix?.let { Geo.formatDistance(Geo.distanceMeters(it.latitude, it.longitude, lat, lon)) })
            } else if (compass.available) {
                OwnHeading(compass)
            }
            if (compass.available && compass.unreliable) OgNote("Kompass ungenau – Handy in einer Acht bewegen.")
            Spacer(Modifier.height(20.dp))
            OgCard {
                Row(
                    // End padding 6 plus the 48 dp touch slot keeps the 44 dp button where the design's 8 dp puts it.
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(Geo.formatCoordinate(lat, lon), style = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = Og.text), modifier = Modifier.weight(1f))
                    if (copied) {
                        Text("Kopiert", style = TextStyle(fontFamily = Grotesk, fontSize = 13.sp, color = Og.ok),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    OgIconButton(
                        if (copied) OgIcon.Check else OgIcon.Copy, "Koordinaten kopieren",
                        {
                            copyCoordinates(context, lat, lon)
                            copied = true
                        },
                        round = false,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                        tint = Og.text2,
                        plainRadius = 12.dp,
                    )
                }
                CardDivider()
                InfoRow(
                    if (out) "Gesendet" else "Empfangen",
                    Formats.time(recordedAt) + (m.accuracyM?.let { " · ${Geo.formatAccuracy(it)}" } ?: ""),
                    last = out,
                )
                if (!out) {
                    InfoRow(
                        "Dein Standort",
                        when {
                            locating && fix == null -> "wird bestimmt …"
                            fix != null -> "${Geo.formatAgo(fix.at, now)} · ${Geo.formatAccuracy(fix.accuracyM)}"
                            else -> "nicht verfügbar"
                        },
                        last = true,
                    )
                }
            }
            if (mapsError) OgNote(LocationError.NO_MAPS_APP.text())
            else if (!out && fix == null) locationError?.let { OgNote(it.text()) }
        }
        Footer(top = 0.dp) {
            OgButton("In Karten-App öffnen", icon = OgIcon.Map, onClick = {
                val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    mapsError = false
                } catch (_: ActivityNotFoundException) {
                    copyCoordinates(context, lat, lon)
                    mapsError = true
                }
            })
            if (!out) {
                OgButton(
                    if (locating) "Wird bestimmt …" else "Meinen Standort aktualisieren",
                    onClick = ::locate,
                    variant = ButtonVariant.Secondary,
                    icon = OgIcon.Refresh,
                    enabled = !locating,
                )
            }
        }
    }
}

/** Dial, distance and direction to a received position. */
@Composable
private fun TargetCompass(compass: CompassState, bearing: Double?, distance: String?) {
    CompassColumn {
        val degrees = bearing?.roundToInt()?.mod(360)
        val direction = bearing?.let { "Richtung ${Geo.compassName(it)}" }
        if (compass.available) {
            val heading = rememberCompassAngle { compass.heading }
            val target by rememberBearingAngle(bearing)
            val ready by remember { derivedStateOf { compass.heading != null } }
            // Spoken as one of eight directions relative to the phone, so TalkBack is not fed every degree.
            val relative = rememberStickySector(bearing) { compass.heading?.let { h -> bearing?.let { Heading.relative(it, h) } } }
            CompassDial(
                label = when {
                    bearing == null -> "Richtung unbekannt"
                    relative == null -> "$direction, $degrees Grad"
                    else -> "Standort ${Heading.relativeName(relative)}, $direction, $degrees Grad"
                },
                roseTurn = { -heading.value },
                arrowTurn = if (bearing != null && ready) ({ target - heading.value }) else null,
                unknown = bearing == null,
            )
        }
        Text(
            distance ?: "–",
            style = TextStyle(
                fontFamily = Grotesk, fontSize = 40.sp, lineHeight = 1.1.em, fontWeight = FontWeight(800),
                letterSpacing = (-0.03).em, color = Og.text,
            ),
        )
        Text(
            if (bearing != null) "$direction · $degrees°" else "Eigener Standort fehlt",
            style = TextStyle(fontFamily = Grotesk, fontSize = 15.sp, color = Og.text2),
        )
        CompassHint(
            if (compass.available) "Halte das Handy flach. Der Pfeil zeigt zum Standort."
            else "Dein Handy hat keinen Kompass. Die Richtung gilt ab Norden.",
        )
    }
}

/** My own position: a north-up dial whose arrow shows where the phone faces. */
@Composable
private fun OwnHeading(compass: CompassState) {
    CompassColumn {
        val heading = rememberCompassAngle { compass.heading }
        val degrees by remember { derivedStateOf { compass.heading?.roundToInt()?.mod(360) } }
        val sector = rememberStickySector(Unit) { compass.heading }
        CompassDial(
            label = sector?.let { "Du schaust nach ${Geo.compassName(it * 45.0)}" } ?: "Kompass wird gelesen",
            roseTurn = { 0f },
            arrowTurn = if (degrees != null) ({ heading.value }) else null,
        )
        Text(
            degrees?.let { "Du schaust nach ${Geo.compassName(it.toDouble())} · $it°" } ?: "Kompass wird gelesen …",
            style = TextStyle(fontFamily = Grotesk, fontSize = 15.sp, color = Og.text2),
        )
        CompassHint("Norden ist oben. Der Pfeil zeigt, wohin dein Handy schaut.")
    }
}

@Composable
private fun CompassColumn(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().padding(start = 24.dp, top = 12.dp, end = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(6.dp),
    content = content,
)

@Composable
private fun CompassHint(text: String) = Text(
    text,
    style = TextStyle(fontFamily = Grotesk, fontSize = 13.sp, color = Og.text3, textAlign = TextAlign.Center),
)

private fun copyCoordinates(context: Context, lat: Double, lon: Double) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Koordinaten", Geo.formatCoordinatePlain(lat, lon)))
}
