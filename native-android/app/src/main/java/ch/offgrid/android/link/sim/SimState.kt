package ch.offgrid.android.link.sim

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Persistent flash content of the simulated Heltec (survives app restarts like real NVS would). */
@Serializable
data class SimFlash(
    val deviceId: String,
    val provisioned: Boolean = false,
    val vaultSalt: String? = null,
    /** SHA-256 of the vault key, stands in for the encrypted check blob of link spec 6.3. */
    val vaultCheck: String? = null,
    val identityPrivate: String? = null,
    val name: String? = null,
    val contacts: List<SimContact> = emptyList(),
    val groups: List<SimGroup> = emptyList(),
    val peers: List<VirtualPeer> = emptyList(),
    val freqMhz: Double = 869.525,
    val sf: Int = 9,
    val bwKhz: Double = 125.0,
    val txPowerDbm: Int = 14,
    val lockTimeoutS: Int = 15,
    /** Lab image `lab.set {text_codec}`, persisted like the lab settings in NVS. */
    val textCodec: String = "raw",
    /** Lab image `lab.set {ignore_nodes}`: NodeIDs whose directly heard packets are dropped. */
    val ignoreNodes: List<String> = emptyList(),
    /** `mesh.set` fields (link spec 8), persisted like `og_cfg`. */
    val hopDefault: Int = 3,
    val relayEnabled: Boolean = true,
    val relayWhenLocked: Boolean = false,
    val retries: Int = 3,
    val cadEnabled: Boolean = true,
)

@Serializable
data class SimContact(
    val contactId: Int,
    val name: String,
    val nodeId: String,
    val publicKey: String,
    val state: String,
    val fingerprint: String,
    val txCtr: Int = 0,
    val rxHighestCtr: Int? = null,
    val lastRssi: Int? = null,
    val lastSnr: Double? = null,
)

/** A member of a simulated group; [contactId] is null for a member the board holds no contact for. */
@Serializable
data class SimGroupMember(val member: Int, val nodeId: String, val contactId: Int? = null)

/** A group slot of the board (link spec 6, "Groups"). [ownMember] is 0 on the owner. */
@Serializable
data class SimGroup(
    val groupId: Int,
    val gid: String,
    val name: String,
    val epoch: Int,
    val owner: Boolean,
    val ownMember: Int,
    val members: List<SimGroupMember>,
    /** Contact slot of the owner on a joined group. */
    val ownerContactId: Int? = null,
    val txCtr: Int = 0,
)

/**
 * A simulated person with their own OffGrid node somewhere in radio range. Used to demo the whole
 * app on an emulator: they scan our code back, acknowledge messages and sometimes reply.
 */
@Serializable
data class VirtualPeer(
    val publicKey: String,
    val privateKey: String,
    val salt: String,
    val name: String,
    val rssi: Int,
    val snr: Double,
    /** Whether the peer scans our QR code too, which completes the mutual pairing. */
    val scansBack: Boolean,
    /** Out-of-range peers never ACK, so messages end as `failed` with reason `timeout`. */
    val inRange: Boolean,
    val replies: List<String> = emptyList(),
    val lat: Double? = null,
    val lon: Double? = null,
)

class SimFlashStore(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): SimFlash? = file?.takeIf { it.exists() }?.let {
        runCatching { json.decodeFromString(SimFlash.serializer(), it.readText()) }.getOrNull()
    }

    fun save(flash: SimFlash) {
        file ?: return
        file.writeText(json.encodeToString(SimFlash.serializer(), flash))
    }

    fun wipe() {
        file?.delete()
    }
}
