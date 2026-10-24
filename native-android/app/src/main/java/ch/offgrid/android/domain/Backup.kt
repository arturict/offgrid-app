package ch.offgrid.android.domain

import ch.offgrid.android.crypto.OgCrypto
import ch.offgrid.android.data.ContactEntity
import ch.offgrid.android.data.GroupEntity
import ch.offgrid.android.data.GroupMemberEntity
import ch.offgrid.android.data.MessageEntity
import ch.offgrid.android.data.OffGridDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.time.Instant

/**
 * The antenna can only be unlocked with a token derived from this phone's Keystore key. A backup
 * therefore carries the token for the paired device, so the new phone can unlock it once and then
 * replace it with its own token through `sec.rotate_token` (link spec 6.10 "needs the old phone's help").
 */
@Serializable
data class UnlockTransfer(val deviceId: String, val token: String)

@Serializable
data class BackupData(
    val format: String = DATA_FORMAT,
    val v: Int = 1,
    val createdAt: Long,
    val displayName: String?,
    val contacts: List<ContactEntity>,
    val messages: List<MessageEntity>,
    val groups: List<GroupEntity> = emptyList(),
    val groupMembers: List<GroupMemberEntity> = emptyList(),
    val unlock: UnlockTransfer? = null,
) {
    companion object {
        const val DATA_FORMAT = "offgrid-backup-data"
    }
}

/** File layout from the team's backup spec: PBKDF2-SHA-256 (210 000 iterations) and AES-256-GCM. */
@Serializable
data class BackupFile(
    val format: String = FILE_FORMAT,
    val v: Int = 1,
    val kdf: String = KDF,
    val iter: Int = ITERATIONS,
    val salt: String,
    val iv: String,
    val data: String,
) {
    companion object {
        const val FILE_FORMAT = "offgrid-backup"
        const val KDF = "pbkdf2-sha256"
        const val ITERATIONS = 210_000
    }
}

enum class BackupError { WRONG_PASSWORD, INVALID_FILE, WRITE_FAILED }

class BackupException(val error: BackupError) : Exception(error.name)

object BackupCodec {
    const val MIN_PASSWORD = 8
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encrypt(data: BackupData, password: String, iterations: Int = BackupFile.ITERATIONS): String {
        val salt = OgCrypto.randomBytes(16)
        val iv = OgCrypto.randomBytes(12)
        val plain = json.encodeToString(BackupData.serializer(), data).toByteArray(Charsets.UTF_8)
        val sealed = OgCrypto.aesGcmEncrypt(deriveKey(password, salt, iterations), iv, plain)
        val file = BackupFile(iter = iterations, salt = OgCrypto.b64(salt), iv = OgCrypto.b64(iv), data = OgCrypto.b64(sealed))
        return json.encodeToString(BackupFile.serializer(), file)
    }

    fun decrypt(text: String, password: String): BackupData {
        val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }.getOrNull()
            ?.takeIf { it.format == BackupFile.FILE_FORMAT && it.v == 1 && it.kdf == BackupFile.KDF && it.iter in 1..10_000_000 }
            ?: throw BackupException(BackupError.INVALID_FILE)
        val plain = try {
            val data = OgCrypto.unb64(file.data)
            // Shorter than the 16-byte GCM tag cannot be a backup. The JDK provider reports such input as
            // ProviderException(ShortBufferException) instead of a tag failure, so it is rejected here.
            if (data.size < 16) throw BackupException(BackupError.INVALID_FILE)
            OgCrypto.aesGcmDecrypt(deriveKey(password, OgCrypto.unb64(file.salt), file.iter), OgCrypto.unb64(file.iv), data)
        } catch (_: AEADBadTagException) {
            throw BackupException(BackupError.WRONG_PASSWORD)
        } catch (_: IllegalArgumentException) {
            throw BackupException(BackupError.INVALID_FILE)
        }
        return runCatching { json.decodeFromString(BackupData.serializer(), plain.toString(Charsets.UTF_8)) }.getOrNull()
            ?.takeIf { it.format == BackupData.DATA_FORMAT }
            ?: throw BackupException(BackupError.INVALID_FILE)
    }

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.also { spec.clearPassword() }
    }
}

/** "Daten exportieren" and "Daten importieren" on top of [BackupCodec]. */
class BackupService(
    private val db: OffGridDatabase,
    private val settings: Settings,
    private val antenna: AntennaManager,
) {
    suspend fun export(password: String, now: Long = System.currentTimeMillis()): String = withContext(Dispatchers.Default) {
        val contacts = db.contacts().all()
        // The token can be derived without a connection; any contact tells which antenna it belongs to.
        val deviceId = antenna.state.value.deviceId ?: contacts.firstOrNull()?.deviceId
        val data = BackupData(
            createdAt = now,
            displayName = settings.get(Settings.DISPLAY_NAME),
            contacts = contacts,
            messages = db.messages().all(),
            groups = db.groups().all(),
            groupMembers = db.groups().allMembers(),
            unlock = deviceId?.let { UnlockTransfer(it, OgCrypto.b64(antenna.tokenFor(it))) },
        )
        BackupCodec.encrypt(data, password)
    }

    /** Replaces all local data, keeps the old phone's token for one unlock, then reconnects. */
    suspend fun import(text: String, password: String) {
        val data = withContext(Dispatchers.Default) { BackupCodec.decrypt(text, password) }
        db.maintenance().replaceAll(data.contacts, data.messages, data.groups, uniqueMemberIndexes(data.groupMembers))
        data.displayName?.let { settings.put(Settings.DISPLAY_NAME, it) }
        settings.put(Settings.ONBOARDED, "true")
        data.unlock?.let {
            settings.put(Settings.TRANSFER_TOKEN, it.token)
            settings.put(Settings.TRANSFER_DEVICE, it.deviceId)
        }
        antenna.disconnect()
        antenna.connect()
    }

    companion object {
        /**
         * Backups from before database version 4 have no member index (it reads as 0 for all of them). Members of one
         * group without distinct indexes get 1, 2, ... in file order, so they fit the (group, member) key.
         */
        internal fun uniqueMemberIndexes(members: List<GroupMemberEntity>): List<GroupMemberEntity> =
            members.groupBy { it.groupId }.values.flatMap { group ->
                if (group.map { it.member }.distinct().size == group.size) group
                else group.mapIndexed { i, m -> m.copy(member = i + 1) }
            }

        /** `offgrid-backup-YYYY-MM-DD.ogx` in Swiss local time. */
        fun fileName(now: Long): String = "offgrid-backup-${Instant.ofEpochMilli(now).atZone(Formats.ZONE).toLocalDate()}.ogx"
    }
}
