package ch.offgrid.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import ch.offgrid.android.data.ContactEntity
import ch.offgrid.android.data.GroupEntity
import ch.offgrid.android.data.GroupMemberEntity
import ch.offgrid.android.data.KeyState
import ch.offgrid.android.domain.Conversation
import ch.offgrid.android.domain.KeyPattern
import ch.offgrid.android.domain.MessageDetails
import ch.offgrid.android.domain.MessageLimits
import ch.offgrid.android.domain.Messaging
import ch.offgrid.android.link.LinkClosedException
import ch.offgrid.android.link.LinkCommandException
import ch.offgrid.android.ui.LocalApp
import ch.offgrid.android.ui.Routes
import ch.offgrid.android.ui.back
import ch.offgrid.android.ui.components.AvatarVariant
import ch.offgrid.android.ui.components.ButtonVariant
import ch.offgrid.android.ui.components.CardDivider
import ch.offgrid.android.ui.components.CheckRow
import ch.offgrid.android.ui.components.Footer
import ch.offgrid.android.ui.components.InfoRow
import ch.offgrid.android.ui.components.MessageBubble
import ch.offgrid.android.ui.components.OgAvatar
import ch.offgrid.android.ui.components.OgBanner
import ch.offgrid.android.ui.components.OgButton
import ch.offgrid.android.ui.components.OgCard
import ch.offgrid.android.ui.components.OgIcon
import ch.offgrid.android.ui.components.OgIconButton
import ch.offgrid.android.ui.components.OgNote
import ch.offgrid.android.ui.components.OgTextField
import ch.offgrid.android.ui.components.OgTopBar
import ch.offgrid.android.ui.components.SectionLabel
import ch.offgrid.android.ui.theme.Grotesk
import ch.offgrid.android.ui.theme.Og
import ch.offgrid.android.ui.theme.OgType
import kotlinx.coroutines.launch

const val GROUP_NAME_ERROR = "Der Gruppenname muss 1 bis 24 Byte lang sein (Umlaute zählen doppelt)."

fun groupPattern(group: GroupEntity): BooleanArray = KeyPattern.fromSeed("group:${group.deviceId}:${group.deviceGroupId}")

/** Ticks or unticks [id] in the member picker; a new member is only added while fewer than [max] are chosen. */
fun toggleMember(selected: List<Long>, id: Long, max: Int): List<Long> = when {
    id in selected -> selected - id
    selected.size < max -> selected + id
    else -> selected
}

/** Group screen "Neue Gruppe": `group.create` on the antenna, then the key goes to every member by `group.sync`. */
@Composable
fun NewGroupScreen(nav: NavHostController) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val contacts by app.messaging.contacts.collectAsStateWithLifecycle(initialValue = emptyList())
    val antenna by app.antenna.state.collectAsStateWithLifecycle()
    val limit = antenna.groups?.maxOtherMembers ?: MessageLimits.MAX_GROUP_MEMBERS
    val active = contacts.filter { it.state == "active" }
    var name by rememberSaveable { mutableStateOf("") }
    // A LongArray is bundle-safe, so the ticks survive rotation and process death.
    var selectedIds by rememberSaveable { mutableStateOf(LongArray(0)) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val nameValid = MessageLimits.isValidName(name)
    // Only contacts that are still active count, in case one was deleted while the picker was open.
    val selected = selectedIds.filter { id -> active.any { it.id == id } }

    Page {
        OgTopBar("Neue Gruppe", onBack = { nav.back() }, backLabel = "Zurück zu den Kontakten")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            OgCard(padded = true, modifier = Modifier.padding(top = 8.dp)) {
                OgTextField("Gruppenname", name, { name = MessageLimits.clampToBytes(it, MessageLimits.MAX_NAME_BYTES) },
                    error = if (name.isNotEmpty() && !nameValid) GROUP_NAME_ERROR else null)
            }
            SectionLabel("Mitglieder · ${selected.size} von $limit", list = false, weight = 500)
            OgCard {
                active.forEachIndexed { i, c ->
                    val checked = c.id in selected
                    CheckRow(
                        name = c.displayName,
                        pattern = patternFor(c.fingerprint),
                        checked = checked,
                        enabled = checked || selected.size < limit,
                        last = i == active.lastIndex,
                    ) { selectedIds = toggleMember(selected, c.id, limit).toLongArray() }
                }
            }
            Text(
                "Nur Kontakte mit abgeschlossenem Handshake. Jedes Mitglied bekommt den Gruppenschlüssel per Funk.",
                style = OgType.help.copy(lineHeight = 1.45.em),
                modifier = Modifier.padding(start = 28.dp, top = 14.dp, end = 28.dp),
            )
            error?.let { OgNote(it) }
        }
        Footer(top = 0.dp) {
            OgButton("Gruppe erstellen", enabled = nameValid && selected.isNotEmpty() && !working, onClick = {
                working = true
                error = null
                scope.launch {
                    app.messaging.createGroup(name, selected)
                        .onSuccess { id -> nav.navigate(Routes.group(id)) { popUpTo(Routes.CONTACTS) } }
                        .onFailure { e ->
                            error = when {
                                e is LinkClosedException -> "Antenne nicht verbunden. Gruppen werden auf der Antenne angelegt."
                                e is LinkCommandException && e.error.code == "E_UNKNOWN_CMD" ->
                                    "Diese Antennen-Firmware kennt noch keine Gruppen."
                                e is LinkCommandException && e.error.code == "E_STORAGE_FULL" ->
                                    "Die Antenne hat keinen freien Gruppenplatz mehr. Lösche zuerst eine Gruppe."
                                else -> "Die Gruppe konnte nicht erstellt werden."
                            }
                        }
                    working = false
                }
            })
        }
    }
}

/** Group chat: sender names over incoming bubbles, no location button, no delivery receipts, text only. */
@Composable
fun GroupChatScreen(nav: NavHostController, groupId: Long) {
    val app = LocalApp.current
    val group by remember(groupId) { app.messaging.group(groupId).asLookup() }.collectAsStateWithLifecycle(Lookup.Loading)
    val messages by app.messaging.groupMessages(groupId).collectAsStateWithLifecycle(initialValue = emptyList())
    val members by app.messaging.members.collectAsStateWithLifecycle(initialValue = emptyList())
    val antenna by app.antenna.state.collectAsStateWithLifecycle()
    val now = rememberNow()
    var draft by rememberSaveable { mutableStateOf("") }
    var detailsFor by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(messages.size) { app.messaging.markGroupRead(groupId) }
    // A deleted group, or a stale id after process death, leads back instead of an empty screen.
    LeaveWhenGone(group == Lookup.Gone, nav)
    ConversationOnScreen(Conversation.Group(groupId))
    val g = group.found ?: run {
        Page {}
        return
    }
    val count = members.count { it.groupId == groupId } + 1
    val pendingKeys = g.owner && members.any { it.groupId == groupId && (it.keyState == KeyState.QUEUED || it.keyState == KeyState.PENDING) }
    val failedKeys = g.owner && members.any { it.groupId == groupId && it.keyState == KeyState.FAILED }

    Box(Modifier.fillMaxSize()) {
        Page {
            ChatHeader(
                title = g.name,
                meta = "Gruppe · $count Mitglieder",
                onBack = { nav.back() },
                avatar = { OgAvatar(groupPattern(g), 40.dp, AvatarVariant.Square) },
                trailing = { OgIconButton(OgIcon.Users, "Mitglieder", { nav.navigate(Routes.groupMembers(groupId)) }) },
            )
            if (!antenna.ready) DisconnectedBanner { nav.navigate(Routes.CONNECT) }
            if (failedKeys) {
                OgBanner("Schlüssel fehlt", "Nicht alle Mitglieder haben den Gruppenschlüssel bekommen. Sie können noch nicht mitlesen.", "Mitglieder") {
                    nav.navigate(Routes.groupMembers(groupId))
                }
            } else if (pendingKeys) {
                OgNote("Der Gruppenschlüssel wird noch an Mitglieder gesendet.")
            }
            MessageList(messages, now, Modifier.weight(1f), empty = "Noch keine Nachrichten. Schreib die erste.") { row ->
                val m = row.message
                MessageBubble(
                    out = m.direction == "out",
                    status = m.status,
                    text = m.text,
                    meta = metaFor(m, antenna.ready),
                    groupStart = row.groupStart,
                    continued = row.continued,
                    senderName = if (row.showSender) m.senderName else null,
                    onRetry = { app.messaging.retry(m.id) },
                    onLongPress = { detailsFor = m.id },
                )
            }
            ChatComposer(
                draft = draft,
                onDraft = { draft = it },
                target = g.name,
                antenna = antenna,
                onSend = {
                    app.messaging.sendGroupText(g.id, draft)
                    draft = ""
                },
                onLocation = null,
                groupMaxBytes = antenna.groups?.maxTextBytes ?: MessageLimits.DEFAULT_MAX_TEXT_BYTES,
            )
        }
        MessageSheet(detailsFor?.let { id -> messages.firstOrNull { it.id == id } }, recipient = null, onClose = { detailsFor = null })
    }
}

/** Key line of a member for the owner; null when there is nothing to deliver. */
fun keyStateText(state: String): String? = when (state) {
    KeyState.DELIVERED -> "Schlüssel zugestellt"
    KeyState.QUEUED, KeyState.PENDING -> "Schlüssel ausstehend"
    KeyState.FAILED -> "Schlüssel fehlgeschlagen"
    else -> null
}

/** A member the antenna holds no contact for is only known by its NodeID. */
fun memberName(member: GroupMemberEntity, contacts: List<ContactEntity>): String =
    contacts.firstOrNull { it.id == member.contactId }?.displayName ?: "Knoten ${member.nodeId}"

/**
 * Members of a group. The owner adds and removes members (each change gives every remaining member a new key) and
 * sees which keys arrived, with "Erneut senden" for those that did not.
 */
@Composable
fun GroupMembersScreen(nav: NavHostController, groupId: Long) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val group by remember(groupId) { app.messaging.group(groupId).asLookup() }.collectAsStateWithLifecycle(Lookup.Loading)
    val allMembers by app.messaging.members.collectAsStateWithLifecycle(initialValue = emptyList())
    val contacts by app.messaging.contacts.collectAsStateWithLifecycle(initialValue = emptyList())
    val antenna by app.antenna.state.collectAsStateWithLifecycle()
    var addIds by rememberSaveable { mutableStateOf(LongArray(0)) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LeaveWhenGone(group == Lookup.Gone, nav)
    val g = group.found ?: run {
        Page {}
        return
    }
    val members = allMembers.filter { it.groupId == groupId }.sortedBy { it.member }
    val limit = antenna.groups?.maxOtherMembers ?: MessageLimits.MAX_GROUP_MEMBERS
    val candidates = contacts.filter { c -> c.state == "active" && members.none { it.contactId == c.id } }
    val selected = addIds.filter { id -> candidates.any { it.id == id } }
    val canChange = g.owner && antenna.ready && g.deviceGroupId != Messaging.NO_GROUP

    fun change(block: suspend () -> Result<Unit>) {
        working = true
        error = null
        scope.launch {
            block().onFailure { e ->
                error = when {
                    e is LinkClosedException -> "Antenne nicht verbunden."
                    e is LinkCommandException && e.error.code == "E_BUSY" ->
                        "Die Gruppe kann nicht mehr geändert werden. Erstelle eine neue Gruppe."
                    e is LinkCommandException && e.error.code == "E_INVALID_ARG" ->
                        "Die Antenne hat die Änderung abgelehnt. Eine Gruppe braucht mindestens zwei Mitglieder."
                    else -> "Die Änderung war nicht möglich."
                }
            }.onSuccess { addIds = LongArray(0) }
            working = false
        }
    }

    Page {
        OgTopBar(g.name, onBack = { nav.back() }, backLabel = "Zurück zur Gruppe")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionLabel("Mitglieder · ${members.size + 1}", list = false, weight = 500)
            OgCard {
                InfoRow("Ich", if (g.owner) "Besitzer" else null, last = members.isEmpty())
                members.forEachIndexed { i, m ->
                    val keyText = if (g.owner) keyStateText(m.keyState) else null
                    Column {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    memberName(m, contacts) + if (!g.owner && m.member == 0) " · Besitzer" else "",
                                    style = OgType.bodyStrong, maxLines = 1,
                                )
                                if (keyText != null) {
                                    val reason = MessageDetails.reason(m.syncReason)?.takeIf { m.keyState == KeyState.FAILED }
                                    Text(listOfNotNull(keyText, reason).joinToString(" · "), style = OgType.help)
                                }
                            }
                            if (m.keyState == KeyState.FAILED) {
                                TextButton(onClick = { app.messaging.resendKey(groupId, m.member) }, enabled = antenna.ready) {
                                    Text("Erneut senden", style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(600), color = Og.text))
                                }
                            }
                            if (canChange) {
                                TextButton(onClick = { change { app.messaging.removeMember(groupId, m.member) } }, enabled = !working) {
                                    Text("Entfernen", style = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(600), color = Og.text))
                                }
                            }
                        }
                        if (i != members.lastIndex) CardDivider()
                    }
                }
            }
            if (g.owner) {
                val room = limit - members.size
                SectionLabel("Hinzufügen · ${selected.size} von $room", list = false, weight = 500)
                if (candidates.isEmpty() || room <= 0) {
                    Text(
                        if (room <= 0) "Die Gruppe ist voll: höchstens $limit Mitglieder und du."
                        else "Alle Kontakte mit abgeschlossenem Handshake sind schon in der Gruppe.",
                        style = OgType.help.copy(lineHeight = 1.45.em),
                        modifier = Modifier.padding(start = 28.dp, end = 28.dp),
                    )
                } else {
                    OgCard {
                        candidates.forEachIndexed { i, c ->
                            val checked = c.id in selected
                            CheckRow(
                                name = c.displayName,
                                pattern = patternFor(c.fingerprint),
                                checked = checked,
                                enabled = canChange && (checked || selected.size < room),
                                last = i == candidates.lastIndex,
                            ) { addIds = toggleMember(selected, c.id, room).toLongArray() }
                        }
                    }
                }
                Text(
                    "Jede Änderung gibt allen Mitgliedern einen neuen Gruppenschlüssel. Entfernte Mitglieder können danach nicht mehr mitlesen.",
                    style = OgType.help.copy(lineHeight = 1.45.em),
                    modifier = Modifier.padding(start = 28.dp, top = 14.dp, end = 28.dp),
                )
            }
            error?.let { OgNote(it) }
            Spacer(Modifier.height(12.dp))
            OgButton("Gruppe löschen", { confirmDelete = true }, variant = ButtonVariant.DangerText, enabled = !working)
        }
        if (g.owner && selected.isNotEmpty()) {
            Footer(top = 0.dp) {
                OgButton("Hinzufügen", enabled = canChange && !working, onClick = { change { app.messaging.addMembers(groupId, selected) } })
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Og.surface,
            shape = RoundedCornerShape(Og.rCard),
            text = {
                Text(
                    "Die Gruppe und ihr Verlauf werden auf diesem Handy und der Antenne gelöscht. Die anderen Mitglieder behalten die Gruppe.",
                    style = OgType.body,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    // App scope: the delete must not be cut off when this screen goes away with the group.
                    app.scope.launch {
                        app.messaging.deleteGroup(groupId).onFailure {
                            error = if (it is LinkClosedException) "Antenne nicht verbunden. Die Gruppe wird auf der Antenne gelöscht." else "Die Gruppe konnte nicht gelöscht werden."
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
