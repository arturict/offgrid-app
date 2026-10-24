package ch.offgrid.android

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.room.Room
import ch.offgrid.android.analytics.Analytics
import ch.offgrid.android.crypto.KeystoreUnlockTokenSource
import ch.offgrid.android.data.MIGRATION_1_2
import ch.offgrid.android.data.MIGRATION_2_3
import ch.offgrid.android.data.MIGRATION_3_4
import ch.offgrid.android.data.OffGridDatabase
import ch.offgrid.android.domain.AntennaManager
import ch.offgrid.android.domain.BackupService
import ch.offgrid.android.domain.LocationService
import ch.offgrid.android.domain.MessageNotifier
import ch.offgrid.android.domain.Messaging
import ch.offgrid.android.domain.Pairing
import ch.offgrid.android.domain.Settings
import ch.offgrid.android.link.BackgroundReceiveService
import ch.offgrid.android.link.LinkKind
import ch.offgrid.android.link.sim.SimFlashStore
import ch.offgrid.android.link.sim.SimulatedHeltec
import ch.offgrid.android.link.sim.SimulatedLink
import ch.offgrid.android.link.hardwareLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

/** Manual dependency container; one per process. */
class AppContainer(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: OffGridDatabase = Room.databaseBuilder(context, OffGridDatabase::class.java, "offgrid.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .build()
    val settings = Settings(db.settings(), scope)

    val simDevice = SimulatedHeltec(SimFlashStore(File(context.filesDir, "sim-heltec.json")), scope)
    val simLink = SimulatedLink(simDevice)
    private val usbLink = hardwareLink(context)

    private val _linkKind = MutableStateFlow(
        runBlocking { settings.get(Settings.LINK_KIND) }?.let(LinkKind::valueOf) ?: defaultLinkKind(),
    )

    /** Simulated antenna by default on the emulator, USB on a real phone; switchable in the demo menu. */
    val linkKind: StateFlow<LinkKind> = _linkKind.asStateFlow()

    val antenna = AntennaManager(
        scope = scope,
        linkProvider = { if (_linkKind.value == LinkKind.SIMULATED) simLink else usbLink },
        tokenSource = KeystoreUnlockTokenSource(),
        settings = settings,
        log = { Log.d("OffGridLink", it) },
    )
    private val analyticsPrefs = context.getSharedPreferences("analytics", Context.MODE_PRIVATE)

    /** Own random analytics ID, separate from the install ID and every key; reset by "Alles löschen". */
    val analytics = Analytics(BuildConfig.POSTHOG_KEY, scope, distinctId = {
        analyticsPrefs.getString(ANALYTICS_ID, null)
            ?: UUID.randomUUID().toString().also { analyticsPrefs.edit().putString(ANALYTICS_ID, it).apply() }
    })
    val notifier = MessageNotifier(context, db)
    val messaging = Messaging(db, antenna, scope, settings, onIncoming = { message ->
        vibrate()
        scope.launch { notifier.onIncoming(message) }
    }, track = analytics::track)
    val pairing = Pairing(antenna, object : Pairing.Store {
        override suspend fun save(deviceId: String, contact: JsonObject) = messaging.upsertFromDevice(deviceId, contact, keepName = false)
        override fun contactState(contactId: Long) = db.contacts().observe(contactId).map { it?.state }
    }, scope, track = analytics::track)
    val location = LocationService(context) { _linkKind.value == LinkKind.SIMULATED }
    val backup = BackupService(db, settings, antenna)

    init {
        antenna.listener = messaging
        // Background receive keeps the process (and so the USB link) alive with a foreground service.
        // Only for USB: Android 14 allows type connectedDevice only after a USB permission grant.
        scope.launch {
            combine(antenna.state, settings.values) { state, values ->
                state.ready && state.kind == LinkKind.USB && values[Settings.BG_RECEIVE] != "false"
            }
                .collect { on -> if (on) BackgroundReceiveService.start(context) else BackgroundReceiveService.stop(context) }
        }
    }

    fun resetAnalyticsId() = analyticsPrefs.edit().remove(ANALYTICS_ID).apply()

    fun setLinkKind(kind: LinkKind) {
        antenna.disconnect()
        _linkKind.value = kind
        scope.launch { settings.put(Settings.LINK_KIND, kind.name) }
    }

    private fun vibrate() {
        scope.launch {
            if (!settings.bool(Settings.VIBRATE, default = false)) return@launch
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return@launch
            vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private companion object {
        const val ANALYTICS_ID = "distinct_id"
    }

    private fun defaultLinkKind(): LinkKind {
        // A debug build with OFFGRID_TCP_LINK has a real antenna behind a bridge, also on the emulator.
        if (BuildConfig.DEBUG && BuildConfig.TCP_LINK.isNotBlank()) return LinkKind.USB
        val emulator = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") ||
            Build.HARDWARE.contains("goldfish") || Build.PRODUCT.contains("sdk")
        return if (emulator) LinkKind.SIMULATED else LinkKind.USB
    }
}
