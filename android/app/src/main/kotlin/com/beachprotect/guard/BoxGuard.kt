package com.beachprotect.guard

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Protects the speaker box.
 *
 * A cheap Bluetooth speaker has no app, no sensors and no way to say "I am
 * being carried off", so we infer it from the only two things it does emit.
 *
 * **1. The audio link.** Exactly one phone holds the A2DP connection. Classic
 * Bluetooth gives up at roughly 10-15 m, and the link also drops the moment the
 * speaker is switched off - which is the first thing anyone walking away with
 * it will do. Either way the guardian phone finds out and alarms the group.
 *
 * A caveat worth knowing: while music is actually streaming, a link loss is
 * noticed within a second or two. While the speaker is merely connected and
 * idle, the drop is only detected when the Bluetooth supervision timeout
 * expires, which is usually a handful of seconds longer.
 *
 * **2. Its BLE beacon.** Many modern speakers also advertise over BLE. When one
 * does, [com.beachprotect.ble.BleScanner] adds its address as a *second
 * hardware filter* alongside the group's service UUID, so tracking the box
 * costs no extra scanning at all. That gives a graded distance reading well
 * before the audio link gives up.
 */
class BoxGuard(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val listener: Listener,
) {

    interface Listener {
        fun onBoxSignal(signal: BoxSignal)
    }

    private var a2dp: BluetoothA2dp? = null
    private var guardedAddress: String? = null
    private var registered = false
    private var lastKnownConnected = false
    private var lastTransitionAt = 0L
    private var flapCount = 0

    /** True while this phone is the one holding the speaker connection. */
    val guardingHere: Boolean get() = lastKnownConnected

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile != BluetoothProfile.A2DP) return
            a2dp = proxy as? BluetoothA2dp
            refresh()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) a2dp = null
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device: BluetoothDevice? = getDevice(intent)
            val address = device?.address ?: return
            if (!address.equals(guardedAddress, ignoreCase = true)) return

            when (action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> emit(true)
                        BluetoothProfile.STATE_DISCONNECTED -> emit(false)
                    }
                }

                BluetoothDevice.ACTION_ACL_CONNECTED -> emit(true)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> emit(false)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getDevice(intent: Intent): BluetoothDevice? =
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    // =====================================================================

    @SuppressLint("MissingPermission")
    fun start(address: String?) {
        guardedAddress = address
        if (address == null) {
            stop()
            return
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            ContextCompat.registerReceiver(
                context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }
        if (a2dp == null) {
            runCatching {
                adapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
            }.onFailure { Log.w(TAG, "cannot bind A2DP profile", it) }
        } else {
            refresh()
        }
    }

    fun stop() {
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        a2dp?.let {
            runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        }
        a2dp = null
        guardedAddress = null
        lastKnownConnected = false
    }

    /** Re-reads the current link state, e.g. after arming. */
    @SuppressLint("MissingPermission")
    fun refresh() {
        val address = guardedAddress ?: return
        val connected = try {
            a2dp?.connectedDevices?.any { it.address.equals(address, ignoreCase = true) } == true
        } catch (e: SecurityException) {
            Log.w(TAG, "missing BLUETOOTH_CONNECT", e)
            return
        }
        emit(connected)
    }

    private fun emit(connected: Boolean) {
        if (connected == lastKnownConnected) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTransitionAt < FLAP_WINDOW_MS) flapCount++ else flapCount = 0
        lastTransitionAt = now
        lastKnownConnected = connected
        listener.onBoxSignal(if (connected) BoxSignal.Connected else BoxSignal.Disconnected)
    }

    /** True when the link has been bouncing, which usually means a weak radio. */
    val flapping: Boolean get() = flapCount >= FLAP_THRESHOLD

    /** Feeds a BLE observation of the box through to the engine. */
    fun onBleRssi(rssi: Int) {
        listener.onBoxSignal(BoxSignal.Rssi(rssi))
    }

    /** Paired audio devices, for the "which speaker is yours?" picker. */
    @SuppressLint("MissingPermission")
    fun pairedAudioDevices(): List<Map<String, Any?>> {
        val adapter = adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.orEmpty().map { device ->
                mapOf(
                    "name" to (device.name ?: device.address),
                    "address" to device.address,
                    "connected" to (
                        a2dp?.connectedDevices?.any { it.address == device.address } == true
                        ),
                )
            }.sortedByDescending { it["connected"] as Boolean }
        } catch (e: SecurityException) {
            Log.w(TAG, "missing BLUETOOTH_CONNECT for bonded devices", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "BpBoxGuard"
        private const val FLAP_WINDOW_MS = 20_000L
        private const val FLAP_THRESHOLD = 4
    }
}
