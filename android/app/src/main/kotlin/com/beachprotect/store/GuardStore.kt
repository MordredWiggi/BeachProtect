package com.beachprotect.store

import android.content.Context
import android.util.Base64
import com.beachprotect.ble.BeaconSource
import com.beachprotect.ble.Protocol
import com.beachprotect.guard.AlarmTarget
import com.beachprotect.guard.DisarmMode
import com.beachprotect.guard.EngineConfig
import com.beachprotect.guard.EngineConfigCodec
import com.beachprotect.guard.PowerProfile
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * Everything that has to survive a restart.
 *
 * There is deliberately exactly one source of truth for configuration, and it
 * lives on the native side. The Flutter UI reads and writes it over the method
 * channel rather than keeping its own copy, because the guard has to keep
 * working correctly long after the Flutter engine has been torn down.
 */
class GuardStore(context: Context) : BeaconSource {

    private val prefs = context.applicationContext
        .getSharedPreferences("beachprotect", Context.MODE_PRIVATE)

    init {
        // Upgrade path. Installs made before first-run completion was tracked
        // already have a group and have long since granted their permissions;
        // sending them back through the wizard would be gratuitous.
        if (!prefs.contains(KEY_ONBOARDED)) {
            prefs.edit().putBoolean(KEY_ONBOARDED, hasGroup).apply()
        }
    }

    // ---- identity --------------------------------------------------------

    /** Random per-install id; combined with the group secret to derive a device id. */
    val installId: String
        get() = prefs.getString(KEY_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_INSTALL_ID, it).apply()
        }

    var groupSecret: ByteArray?
        get() = prefs.getString(KEY_GROUP_SECRET, null)
            ?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?.takeIf { it.size == 10 }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_GROUP_SECRET)
            } else {
                editor.putString(KEY_GROUP_SECRET, Base64.encodeToString(value, Base64.NO_WRAP))
            }
            editor.apply()
        }

    val hasGroup: Boolean get() = groupSecret != null

    var groupName: String
        get() = prefs.getString(KEY_GROUP_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GROUP_NAME, value).apply()

    var selfName: String
        get() = prefs.getString(KEY_SELF_NAME, "") ?: ""
        set(value) = prefs.edit()
            .putString(KEY_SELF_NAME, Protocol.normaliseName(value)).apply()

    override val groupId: Int get() = groupSecret?.let { Protocol.groupIdOf(it) } ?: 0
    override val groupKey: ByteArray get() = groupSecret?.let { Protocol.groupKeyOf(it) } ?: ByteArray(32)
    override val deviceId: Int
        get() = groupSecret?.let { Protocol.deviceIdOf(it, installId) } ?: 0

    val groupCode: String? get() = groupSecret?.let { Protocol.encodeGroupCode(it) }

    /**
     * The advertisement sequence counter deliberately survives all three of
     * these.
     *
     * It used to be reset whenever the group changed, which quietly broke
     * rejoining: this device's id is derived from the group secret and the
     * install id, so leaving a group and joining it again comes back as the
     * *same* device with its counter back at zero. Every phone that still
     * remembered the old, higher number then treated its "arm everyone" and
     * "everybody stop" as replays and threw them away — for as long as it
     * remembered the peer at all. A counter that only ever climbs cannot do
     * that, and monotonic-for-the-life-of-the-install is a stronger guarantee
     * than monotonic-per-group anyway.
     */
    fun createGroup(name: String): String {
        val secret = Protocol.newGroupSecret()
        groupSecret = secret
        groupName = name
        forgetPeerIdentities()
        return Protocol.encodeGroupCode(secret)
    }

    /** @return true when [code] was a well formed group code. */
    fun joinGroup(code: String, name: String): Boolean {
        val secret = Protocol.decodeGroupCode(code) ?: return false
        groupSecret = secret
        groupName = name
        forgetPeerIdentities()
        return true
    }

    /**
     * Everything keyed by device id, dropped whenever the group changes.
     *
     * Device ids are a hash of the group secret and the peer's install id, so the
     * same friend is a different id in a different group - and a name left over
     * from the old one would either vanish or, if two ids collided, be attached to
     * the wrong phone.
     */
    private fun forgetPeerIdentities() {
        peerNames = emptyMap()
        learnedPeerNames = emptyMap()
    }

    fun leaveGroup() {
        groupSecret = null
        groupName = ""
        armed = false
        peerNames = emptyMap()
        // Device ids are derived from the group secret, so nothing learned in the
        // old group can mean anything in the next one.
        learnedPeerNames = emptyMap()
    }

    // ---- monotonic sequence ---------------------------------------------

    /**
     * Reserves a block of advertisement sequence numbers and returns its base.
     *
     * The sequence number is what stops a recorded "disarm everyone" packet
     * from being replayed the next afternoon, so it must never go backwards or
     * repeat - not even across a crash. Persisting every single number would
     * mean a flash write every second for hours, so instead we persist the
     * *end* of a block up front and hand out numbers from memory. A crash
     * simply burns the rest of the block.
     */
    override fun beginSequenceBlock(): Int {
        val base = prefs.getInt(KEY_SEQ, 0)
        prefs.edit().putInt(KEY_SEQ, base + SEQ_BLOCK).apply()
        return base
    }

    /** Size of a reserved block; about an hour of beaconing. */
    override val sequenceBlockSize: Int get() = SEQ_BLOCK

    /**
     * The next group-command counter, wrapping at a byte.
     *
     * Identifies one press of one button. Persisted — one write per press, which
     * is nothing — because it has to keep climbing across restarts: a phone that
     * came back at zero would have its next hundred-odd commands read as stale by
     * everyone who still remembered the old number. (Clearing app data resets it,
     * but that also changes the install id and therefore the device id, so it
     * comes back as a device nobody has heard of.)
     */
    fun nextCommandCounter(): Int {
        val next = (prefs.getInt(KEY_COMMAND_COUNTER, 0) + 1) and 0xFF
        prefs.edit().putInt(KEY_COMMAND_COUNTER, next).apply()
        return next
    }

    // ---- disarm authentication -------------------------------------------

    var disarmMode: DisarmMode
        get() = runCatching {
            DisarmMode.valueOf(prefs.getString(KEY_DISARM_MODE, null) ?: "")
        }.getOrDefault(DisarmMode.BIOMETRIC_WITH_PIN)
        set(value) = prefs.edit().putString(KEY_DISARM_MODE, value.name).apply()

    val hasPin: Boolean get() = prefs.getString(KEY_PIN_HASH, null) != null

    /** PINs are stored salted and hashed, never in the clear. */
    fun setPin(pin: String) {
        if (pin.isEmpty()) {
            prefs.edit().remove(KEY_PIN_HASH).remove(KEY_PIN_SALT).apply()
            return
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(hashPin(pin, salt), Base64.NO_WRAP))
            .apply()
    }

    fun checkPin(pin: String): Boolean {
        val saltEncoded = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val hashEncoded = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val salt = Base64.decode(saltEncoded, Base64.NO_WRAP)
        val expected = Base64.decode(hashEncoded, Base64.NO_WRAP)
        val actual = hashPin(pin, salt)
        if (expected.size != actual.size) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor actual[i].toInt())
        return diff == 0
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        // Iterated SHA-256. A 4-6 digit PIN has little entropy, so this is
        // about slowing down an attacker with a rooted phone, not about
        // making the PIN itself strong.
        val md = MessageDigest.getInstance("SHA-256")
        var digest = md.digest(salt + pin.toByteArray())
        repeat(20_000) {
            md.reset()
            digest = md.digest(digest + salt)
        }
        return digest
    }

    // ---- behaviour -------------------------------------------------------

    var powerProfile: PowerProfile
        get() = runCatching {
            PowerProfile.valueOf(prefs.getString(KEY_POWER_PROFILE, null) ?: "")
        }.getOrDefault(PowerProfile.BALANCED)
        set(value) = prefs.edit().putString(KEY_POWER_PROFILE, value.name).apply()

    var alarmTarget: AlarmTarget
        get() = runCatching {
            AlarmTarget.valueOf(prefs.getString(KEY_ALARM_TARGET, null) ?: "")
        }.getOrDefault(AlarmTarget.BOX_AND_PHONES)
        set(value) = prefs.edit().putString(KEY_ALARM_TARGET, value.name).apply()

    var sirenVolume: Float
        get() = prefs.getFloat(KEY_SIREN_VOLUME, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SIREN_VOLUME, value.coerceIn(0.1f, 1.0f)).apply()

    var vibrateOnAlarm: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATE, value).apply()

    var speakReason: Boolean
        get() = prefs.getBoolean(KEY_SPEAK, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEAK, value).apply()

    /** Whether the guard should be running. Restored after a reboot or crash. */
    var armed: Boolean
        get() = prefs.getBoolean(KEY_ARMED, false)
        set(value) = prefs.edit().putBoolean(KEY_ARMED, value).apply()

    var simulationEnabled: Boolean
        get() = prefs.getBoolean(KEY_SIM, false)
        set(value) = prefs.edit().putBoolean(KEY_SIM, value).apply()

    /**
     * Whether the first-run walkthrough has been seen all the way through.
     *
     * Tracked separately from "has a group", because creating the group is
     * only step two of four. Keying the first run off the group alone dropped
     * the user onto the home screen the instant they tapped "Create group",
     * skipping the PIN and the whole permissions walkthrough - leaving a guard
     * that could not raise a lock-screen prompt and would be suspended by the
     * battery manager within the hour.
     */
    var onboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    /** Calibrated RSSI at one metre, broadcast so peers can estimate distance. */
    override var txPowerRef: Int
        get() = prefs.getInt(KEY_TX_POWER_REF, -59)
        set(value) = prefs.edit().putInt(KEY_TX_POWER_REF, value.coerceIn(-100, -20)).apply()

    // ---- box -------------------------------------------------------------

    var boxEnabled: Boolean
        get() = prefs.getBoolean(KEY_BOX_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BOX_ENABLED, value).apply()

    var boxAddress: String?
        get() = prefs.getString(KEY_BOX_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_BOX_ADDRESS, value).apply()

    var boxName: String?
        get() = prefs.getString(KEY_BOX_NAME, null)
        set(value) = prefs.edit().putString(KEY_BOX_NAME, value).apply()

    /**
     * BLE address of the box, when it advertises one.
     *
     * Often identical to the classic address, but plenty of speakers use a
     * neighbouring address for their BLE radio, so it is discovered and stored
     * separately.
     */
    var boxBleAddress: String?
        get() = prefs.getString(KEY_BOX_BLE_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_BOX_BLE_ADDRESS, value).apply()

    // ---- peer nicknames --------------------------------------------------

    var peerNames: Map<Int, String>
        get() {
            val raw = prefs.getString(KEY_PEER_NAMES, null) ?: return emptyMap()
            return runCatching {
                val json = JSONObject(raw)
                json.keys().asSequence().associate { it.toInt() to json.getString(it) }
            }.getOrDefault(emptyMap())
        }
        set(value) {
            val json = JSONObject()
            value.forEach { (id, name) -> json.put(id.toString(), name) }
            prefs.edit().putString(KEY_PEER_NAMES, json.toString()).apply()
        }

    fun renamePeer(deviceId: Int, name: String?) {
        val next = peerNames.toMutableMap()
        if (name.isNullOrBlank()) next.remove(deviceId) else next[deviceId] = name
        peerNames = next
    }

    /**
     * Names peers have told us themselves, as opposed to nicknames the user
     * typed.
     *
     * Kept separately and persisted because they are expensive to learn: a name
     * travels two characters at a time and needs six different packets to land, so
     * losing one because the peer went quiet for five minutes or the service was
     * restarted meant the group list dropping back to hexadecimal ids - which
     * from the outside is indistinguishable from names never having worked.
     */
    var learnedPeerNames: Map<Int, String>
        get() {
            val raw = prefs.getString(KEY_LEARNED_NAMES, null) ?: return emptyMap()
            return runCatching {
                val json = JSONObject(raw)
                json.keys().asSequence().associate { it.toInt() to json.getString(it) }
            }.getOrDefault(emptyMap())
        }
        set(value) {
            val json = JSONObject()
            value.forEach { (id, name) -> json.put(id.toString(), name) }
            prefs.edit().putString(KEY_LEARNED_NAMES, json.toString()).apply()
        }

    fun rememberLearnedName(deviceId: Int, name: String) {
        if (learnedPeerNames[deviceId] == name) return
        learnedPeerNames = learnedPeerNames + (deviceId to name)
    }

    // ---- detector tuning -------------------------------------------------

    var engineConfig: EngineConfig
        get() {
            val defaults = EngineConfig()
            return defaults.copy(
                dropThresholdDb = prefs.getFloat(KEY_DROP_DB, defaults.dropThresholdDb.toFloat())
                    .toDouble(),
                sustainMs = prefs.getLong(KEY_SUSTAIN_MS, defaults.sustainMs),
                consensusRatio = prefs.getFloat(
                    KEY_CONSENSUS_RATIO, defaults.consensusRatio.toFloat(),
                ).toDouble(),
                minObservers = prefs.getInt(KEY_MIN_OBSERVERS, defaults.minObservers),
                lostTimeoutMs = prefs.getLong(KEY_LOST_TIMEOUT_MS, defaults.lostTimeoutMs),
                pickupGraceMs = prefs.getLong(KEY_PICKUP_GRACE_MS, defaults.pickupGraceMs),
                settleMs = prefs.getLong(KEY_SETTLE_MS, defaults.settleMs),
                motionScoreThreshold = prefs.getInt(
                    KEY_MOTION_THRESHOLD, defaults.motionScoreThreshold,
                ),
                alarmOnPickupAlone = prefs.getBoolean(KEY_PICKUP_ALONE, defaults.alarmOnPickupAlone),
            )
        }
        set(value) = prefs.edit()
            .putFloat(KEY_DROP_DB, value.dropThresholdDb.toFloat())
            .putLong(KEY_SUSTAIN_MS, value.sustainMs)
            .putFloat(KEY_CONSENSUS_RATIO, value.consensusRatio.toFloat())
            .putInt(KEY_MIN_OBSERVERS, value.minObservers)
            .putLong(KEY_LOST_TIMEOUT_MS, value.lostTimeoutMs)
            .putLong(KEY_PICKUP_GRACE_MS, value.pickupGraceMs)
            .putLong(KEY_SETTLE_MS, value.settleMs)
            .putInt(KEY_MOTION_THRESHOLD, value.motionScoreThreshold)
            .putBoolean(KEY_PICKUP_ALONE, value.alarmOnPickupAlone)
            .apply()

    fun restoreDetectorDefaults() {
        engineConfig = EngineConfig()
    }

    // ---- bridge serialisation --------------------------------------------

    /** Snapshot for the Flutter settings screen. */
    fun toMap(): Map<String, Any?> {
        return EngineConfigCodec.toMap(engineConfig) + mapOf(
            "hasGroup" to hasGroup,
            "groupName" to groupName,
            "groupCode" to groupCode,
            "selfName" to selfName,
            "deviceId" to deviceId,
            "disarmMode" to disarmMode.name,
            "hasPin" to hasPin,
            "powerProfile" to powerProfile.name,
            "alarmTarget" to alarmTarget.name,
            "sirenVolume" to sirenVolume.toDouble(),
            "vibrateOnAlarm" to vibrateOnAlarm,
            "speakReason" to speakReason,
            "armed" to armed,
            "simulationEnabled" to simulationEnabled,
            "onboardingComplete" to onboardingComplete,
            "txPowerRef" to txPowerRef,
            "boxEnabled" to boxEnabled,
            "boxAddress" to boxAddress,
            "boxName" to boxName,
            "boxBleAddress" to boxBleAddress,
            "peerNames" to peerNames.mapKeys { it.key.toString() },
        )
    }

    /** Applies a partial settings patch coming from the UI. */
    fun applyMap(patch: Map<String, Any?>) {
        (patch["selfName"] as? String)?.let { selfName = it }
        (patch["groupName"] as? String)?.let { groupName = it }
        (patch["disarmMode"] as? String)?.let {
            runCatching { disarmMode = DisarmMode.valueOf(it) }
        }
        (patch["powerProfile"] as? String)?.let {
            runCatching { powerProfile = PowerProfile.valueOf(it) }
        }
        (patch["alarmTarget"] as? String)?.let {
            runCatching { alarmTarget = AlarmTarget.valueOf(it) }
        }
        (patch["sirenVolume"] as? Number)?.let { sirenVolume = it.toFloat() }
        (patch["vibrateOnAlarm"] as? Boolean)?.let { vibrateOnAlarm = it }
        (patch["speakReason"] as? Boolean)?.let { speakReason = it }
        (patch["simulationEnabled"] as? Boolean)?.let { simulationEnabled = it }
        (patch["onboardingComplete"] as? Boolean)?.let { onboardingComplete = it }
        (patch["txPowerRef"] as? Number)?.let { txPowerRef = it.toInt() }
        (patch["boxEnabled"] as? Boolean)?.let { boxEnabled = it }
        // Nullable fields are keyed on presence, not on the value: "forget this
        // speaker" sends an explicit null, and a `?.let` would silently skip it,
        // leaving the old address in place forever.
        if (patch.containsKey("boxAddress")) boxAddress = patch["boxAddress"] as? String
        if (patch.containsKey("boxName")) boxName = patch["boxName"] as? String
        if (patch.containsKey("boxBleAddress")) {
            boxBleAddress = patch["boxBleAddress"] as? String
        }
        (patch["pin"] as? String)?.let { setPin(it) }

        engineConfig = EngineConfigCodec.apply(engineConfig, patch)
    }

    companion object {
        private const val SEQ_BLOCK = 4096

        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_GROUP_SECRET = "group_secret"
        private const val KEY_GROUP_NAME = "group_name"
        private const val KEY_SELF_NAME = "self_name"
        private const val KEY_SEQ = "seq"
        private const val KEY_COMMAND_COUNTER = "command_counter"
        private const val KEY_DISARM_MODE = "disarm_mode"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_POWER_PROFILE = "power_profile"
        private const val KEY_ALARM_TARGET = "alarm_target"
        private const val KEY_SIREN_VOLUME = "siren_volume"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_SPEAK = "speak"
        private const val KEY_ARMED = "armed"
        private const val KEY_SIM = "sim_enabled"
        private const val KEY_ONBOARDED = "onboarding_complete"
        private const val KEY_TX_POWER_REF = "tx_power_ref"
        private const val KEY_BOX_ENABLED = "box_enabled"
        private const val KEY_BOX_ADDRESS = "box_address"
        private const val KEY_BOX_NAME = "box_name"
        private const val KEY_BOX_BLE_ADDRESS = "box_ble_address"
        private const val KEY_PEER_NAMES = "peer_names"
        private const val KEY_LEARNED_NAMES = "learned_peer_names"
        private const val KEY_DROP_DB = "drop_db"
        private const val KEY_SUSTAIN_MS = "sustain_ms"
        private const val KEY_CONSENSUS_RATIO = "consensus_ratio"
        private const val KEY_MIN_OBSERVERS = "min_observers"
        private const val KEY_LOST_TIMEOUT_MS = "lost_timeout_ms"
        private const val KEY_PICKUP_GRACE_MS = "pickup_grace_ms"
        private const val KEY_SETTLE_MS = "settle_ms"
        private const val KEY_MOTION_THRESHOLD = "motion_threshold"
        private const val KEY_PICKUP_ALONE = "pickup_alone"
    }
}
