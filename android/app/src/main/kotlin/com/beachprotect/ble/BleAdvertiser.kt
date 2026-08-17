package com.beachprotect.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.beachprotect.guard.RadioProfile
import java.util.UUID

/**
 * Broadcasts this phone's beacon.
 *
 * Uses `startAdvertisingSet` (API 26+) rather than the older `startAdvertising`
 * because it can rewrite the payload *in place*. That matters a lot here: the
 * payload changes every second or so (motion score, votes, name chunks), and
 * the legacy API would need a stop/start cycle for every one of those, which is
 * both slow and a good way to get throttled by the Bluetooth stack.
 *
 * ## Why the transmit power never changes
 *
 * It would be tempting to turn the transmitter up when something looks wrong.
 * We deliberately never do. Observers detect theft by comparing RSSI against a
 * learned baseline, so raising TX power mid-incident would lift every observer's
 * reading and mask exactly the drop we are trying to catch. The interval speeds
 * up under suspicion; the power stays put.
 */
class BleAdvertiser(private val adapter: BluetoothAdapter?) {

    private val handler = Handler(Looper.getMainLooper())
    private var advertisingSet: AdvertisingSet? = null
    private var currentProfile: RadioProfile? = null
    private var payload: ByteArray? = null

    /** True between requesting a start and the stack confirming it. */
    var starting = false
        private set

    /**
     * False when this phone cannot advertise at all, so the UI can say why the
     * others cannot see it.
     *
     * Deliberately *not* `isMultipleAdvertisementSupported`, which is the usual
     * shorthand and is wrong here: it answers "can several advertising sets run
     * at once", and we only ever run one. Several otherwise perfectly capable
     * phones report false for it, and taking it at its word left them silently
     * invisible to their own group while their radio was ready to go.
     */
    val supported: Boolean
        get() = adapter?.bluetoothLeAdvertiser != null

    /** Set when the stack rejected a start outright; surfaced as a warning. */
    var startRejected = false
        private set

    var running: Boolean = false
        private set

    private val callback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            starting = false
            if (status != ADVERTISE_SUCCESS) {
                Log.w(TAG, "advertising set failed to start: status=$status")
                running = false
                startRejected = true
                return
            }
            startRejected = false
            advertisingSet = set
            running = true
            // A payload may have been queued while the set was coming up.
            payload?.let { pushPayload(it) }
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            // Re-tuning stops the old set and immediately starts a new one.
            // The stop callback can land *after* the new set is already up, and
            // clearing our state here would silently kill the advertisement.
            if (starting) return
            advertisingSet = null
            running = false
        }

        override fun onAdvertisingDataSet(set: AdvertisingSet?, status: Int) {
            if (status != ADVERTISE_SUCCESS) {
                Log.w(TAG, "advertising data rejected: status=$status")
            }
        }
    }

    /**
     * Starts, or re-tunes, the advertiser.
     *
     * Changing the interval means tearing the set down and building a new one,
     * so this is a no-op when the profile has not actually changed.
     */
    @SuppressLint("MissingPermission")
    fun start(profile: RadioProfile, initialPayload: ByteArray) {
        payload = initialPayload
        if (!supported) return
        if (running && currentProfile == profile) {
            pushPayload(initialPayload)
            return
        }
        if (starting) return

        stop()
        currentProfile = profile
        starting = true

        val parameters = AdvertisingSetParameters.Builder()
            // Legacy PDUs: every Android phone since 5.0 can see them, whereas
            // extended advertising needs BLE 5 on both ends.
            .setLegacyMode(true)
            .setConnectable(false)
            .setScannable(false)
            .setInterval(intervalFor(profile))
            // Constant, on purpose. See the class comment.
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(SERVICE_PARCEL_UUID, initialPayload)
            .build()

        try {
            adapter?.bluetoothLeAdvertiser?.startAdvertisingSet(
                parameters, data, null, null, null, callback,
            )
        } catch (e: SecurityException) {
            starting = false
            Log.w(TAG, "missing BLUETOOTH_ADVERTISE permission", e)
        } catch (e: IllegalArgumentException) {
            starting = false
            Log.w(TAG, "advertising parameters rejected", e)
        }
    }

    /** Rewrites the broadcast payload without interrupting the advertisement. */
    fun updatePayload(bytes: ByteArray) {
        payload = bytes
        pushPayload(bytes)
    }

    @SuppressLint("MissingPermission")
    private fun pushPayload(bytes: ByteArray) {
        val set = advertisingSet ?: return
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(SERVICE_PARCEL_UUID, bytes)
            .build()
        try {
            set.setAdvertisingData(data)
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot update advertising data", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser != null && (running || starting)) {
            try {
                advertiser.stopAdvertisingSet(callback)
            } catch (e: SecurityException) {
                Log.w(TAG, "cannot stop advertising", e)
            }
        }
        advertisingSet = null
        running = false
        starting = false
        currentProfile = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun intervalFor(profile: RadioProfile): Int = when (profile) {
        // ~1 s. The cheapest legal interval, and plenty when nothing is wrong.
        RadioProfile.CALM -> AdvertisingSetParameters.INTERVAL_HIGH
        // ~250 ms, so observers get four RSSI samples a second while they decide.
        RadioProfile.ALERT -> AdvertisingSetParameters.INTERVAL_MEDIUM
        // ~100 ms, so the group reacts as close to instantly as BLE allows.
        RadioProfile.CRITICAL -> AdvertisingSetParameters.INTERVAL_LOW
    }

    companion object {
        private const val TAG = "BpAdvertiser"
        val SERVICE_UUID: UUID = UUID.fromString(Protocol.SERVICE_UUID_STRING)
        val SERVICE_PARCEL_UUID = ParcelUuid(SERVICE_UUID)
    }
}
