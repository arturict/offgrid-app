package ch.offgrid.android.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ch.offgrid.android.BuildConfig
import ch.offgrid.android.data.MessageEntity
import ch.offgrid.android.domain.AntennaActivity
import ch.offgrid.android.domain.AntennaPhase
import ch.offgrid.android.domain.AntennaState
import ch.offgrid.android.domain.ConversationItem
import ch.offgrid.android.domain.Formats
import ch.offgrid.android.domain.KeyPattern
import ch.offgrid.android.domain.Messaging
import ch.offgrid.android.domain.Settings
import ch.offgrid.android.link.LinkKind
import ch.offgrid.android.ui.LocalApp
import ch.offgrid.android.ui.Routes
import ch.offgrid.android.ui.components.AvatarVariant
import ch.offgrid.android.ui.components.Footer
import ch.offgrid.android.ui.components.OgAvatar
import ch.offgrid.android.ui.components.OgBadge
import ch.offgrid.android.ui.components.OgBanner
import ch.offgrid.android.ui.components.OgButton
import ch.offgrid.android.ui.components.OgIcon
import ch.offgrid.android.ui.components.OgIconButton
import ch.offgrid.android.ui.components.SignalBars
import ch.offgrid.android.ui.components.StatusDot
import ch.offgrid.android.ui.theme.Og
import ch.offgrid.android.ui.theme.OgType
import kotlinx.coroutines.launch

/** Screen 3 "Kontakte" with the empty state "Noch keine Kontakte". */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactsScreen(nav: NavHostController) {
    val app = LocalApp.current
    val items by app.messaging.conversations.collectAsStateWithLifecycle(initialValue = null)
    val antenna by app.antenna.state.collectAsStateWithLifecycle()
    val settings by app.settings.values.collectAsStateWithLifecycle()
    val installId by produceState<String?>(null) { value = app.settings.installId() }
    val now = rememberNow()
    var demoMenu by remember { mutableStateOf(false) }
    val list = items ?: return
    val empty = list.isEmpty()
    val ownPattern = remember(settings[Settings.OWN_PUBLIC_KEY], installId) {
        KeyPattern.fromSeed(settings[Settings.OWN_PUBLIC_KEY] ?: installId ?: "")
    }

    Box(Modifier.fillMaxSize()) {
        Page {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, top = 22.dp, end = 20.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "OffGrid",
                    style = OgType.appTitle,
                    // Debug builds open the test menu with a long press; release builds keep a plain heading.
                    modifier = Modifier.semantics { heading() }.then(
                        if (BuildConfig.DEBUG) {
                            Modifier.combinedClickable(onClick = {}, onLongClick = { demoMenu = true }, onLongClickLabel = "Testmenü öffnen")
                        } else {
                            Modifier
                        },
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OgIconButton(OgIcon.Settings, "Einstellungen", { nav.navigate(Routes.SETTINGS) })
                    if (!empty) {
                        // Only firmware that lists the "groups" feature in sys.hello has group.* commands.
                        if (antenna.groups != null && list.count { it.contact?.state == "active" } > 0) {
                            OgIconButton(OgIcon.Users, "Gruppe erstellen", { nav.navigate(Routes.NEW_GROUP) })
                        }
                        OgIconButton(OgIcon.Plus, "Kontakt hinzufügen", { nav.navigate(Routes.add()) })
                    }
                }
            }
            AntennaStatusLine(antenna) { nav.navigate(Routes.CONNECT) }
            settings[Settings.REPAIR_NOTICE]?.toIntOrNull()?.takeIf { it > 0 }?.let { n ->
                val who = if (n == 1) "einen Kontakt" else "$n Kontakte"
                OgBanner(
                    "Neu koppeln",
                    "Die Antenne kennt $who nicht mehr, weil sie zurückgesetzt oder neu geflasht wurde. " +
                        "Scannt eure Codes erneut, dann könnt ihr wieder schreiben.",
                    "OK",
                ) { app.scope.launch { app.settings.remove(Settings.REPAIR_NOTICE) } }
            }
            HorizontalDivider(Modifier.padding(start = 20.dp, top = 6.dp, end = 20.dp, bottom = if (empty) 0.dp else 8.dp), thickness = 1.dp, color = Og.line)

            if (empty) {
                EmptyState(ownPattern, Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    items(list, key = { (it.contact?.id ?: -(it.group?.id ?: 0)).toString() + (it.group != null) }) { item ->
                        ConversationRow(item, now, onOpen = {
                            val contact = item.contact
                            when {
                                // A pending contact still needs our code (Kontakte.dc.html links it to the handshake).
                                contact?.state == "pending" -> nav.navigate(Routes.add())
                                contact != null -> nav.navigate(Routes.chat(contact.id))
                                else -> item.group?.let { nav.navigate(Routes.group(it.id)) }
                            }
                        })
                    }
                }
            }
            Footer {
                OgButton(if (empty) "Ersten Kontakt hinzufügen" else "Kontakt hinzufügen", { nav.navigate(Routes.add()) }, icon = OgIcon.Qr)
            }
        }
        if (BuildConfig.DEBUG) DemoMenu(demoMenu, onClose = { demoMenu = false })
    }
}

/**
 * "● Antenne verbunden · USB-C · 868 MHz", tappable to the connect screen. With large fonts the
 * meta part moves to its own line as a whole instead of breaking word by word.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AntennaStatusLine(antenna: AntennaState, onClick: () -> Unit) {
    val ready = antenna.ready
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(ready)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            Text(antennaStatusTitle(antenna), style = OgType.preview.copy(color = Og.text2),
                modifier = Modifier.align(Alignment.CenterVertically))
            val transport = if (antenna.kind == LinkKind.SIMULATED) "Simuliert" else "USB-C"
            Text(
                when {
                    ready && antenna.radioRunning == false -> "· Funkmodul nicht bereit"
                    ready -> "· $transport · ${antenna.freqMhz?.let(Formats::band) ?: "868"} MHz"
                    antenna.phase == AntennaPhase.CONNECTING -> "· ${antenna.activity?.text() ?: "verbindet …"}"
                    antenna.busy -> "· sucht …"
                    else -> "· Tippen zum Verbinden"
                },
                style = OgType.meta,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

/** First half of the status line: connected, being opened, locked with an error, or away. */
fun antennaStatusTitle(antenna: AntennaState): String = when {
    antenna.ready -> "Antenne verbunden"
    antenna.phase == AntennaPhase.CONNECTING && antenna.activity == AntennaActivity.RELOCKING -> "Antenne gesperrt"
    antenna.busy -> "Antenne wird verbunden"
    antenna.phase == AntennaPhase.ERROR && antenna.deviceState == "locked" -> "Antenne gesperrt"
    antenna.phase == AntennaPhase.ERROR -> "Antenne: Fehler"
    else -> "Antenne getrennt"
}

@Composable
private fun ConversationRow(item: ConversationItem, now: Long, onOpen: () -> Unit) {
    val contact = item.contact
    val pending = contact?.state == "pending"
    // The antenna lost this contact's keys; the row keeps the history but needs a new pairing.
    val unpaired = contact?.state == Messaging.UNPAIRED
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Og.rBtn))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            item.group != null -> OgAvatar(groupPattern(item.group), 48.dp, AvatarVariant.Square)
            pending || unpaired -> OgAvatar(null, 48.dp, AvatarVariant.Pending)
            else -> OgAvatar(patternFor(contact!!.fingerprint), 48.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(item.group?.name ?: contact!!.displayName, style = OgType.bodyStrong, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (contact != null) {
                    val level = if (pending || unpaired) 0 else Formats.signalLevel(contact.lastRssi, contact.lastSeenAt, now)
                    SignalBars(level, label = if (pending || unpaired) null else Formats.signalLabel(level))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pending) {
                    Text("Schlüssel ausstehend", style = OgType.preview.copy(color = Og.accent), modifier = Modifier.weight(1f))
                } else if (unpaired) {
                    Text("Neu koppeln", style = OgType.preview.copy(color = Og.danger), modifier = Modifier.weight(1f))
                } else {
                    Text(
                        preview(item),
                        style = OgType.preview.copy(color = if (item.unread > 0) Og.text2 else Og.text3),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OgBadge(item.unread)
                        item.last?.let { Text(Formats.listTime(it.sentAt, now), style = OgType.meta) }
                    }
                }
            }
        }
    }
}

private fun preview(item: ConversationItem): String {
    val last: MessageEntity = item.last ?: return if (item.group != null) "Gruppe · ${item.memberCount} Mitglieder" else "Noch keine Nachrichten"
    val body = if (last.kind == "geo") "Standort" else last.text
    return when {
        last.direction == "out" -> "Du: $body"
        item.group != null -> "${last.senderName ?: "?"}: $body"
        else -> body
    }
}

@Composable
private fun EmptyState(ownPattern: BooleanArray, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 36.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OgAvatar(ownPattern, 56.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { Box(Modifier.size(5.dp).clip(CircleShape).background(Og.borderMuted)) }
            }
            OgAvatar(null, 56.dp, AvatarVariant.Pending)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Noch keine Kontakte", style = OgType.emptyTitle, modifier = Modifier.semantics { heading() })
            Text(
                "Triff dich mit jemandem, der OffGrid nutzt, und scannt gegenseitig eure Codes. Danach könnt ihr ohne Mobilnetz schreiben.",
                style = OgType.body2.copy(textAlign = TextAlign.Center),
            )
        }
    }
}
