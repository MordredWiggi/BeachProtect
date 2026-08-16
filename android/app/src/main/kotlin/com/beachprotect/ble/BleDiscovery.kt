package com.beachprotect.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * One-shot, unfiltered BLE scan used only when setting up the speaker box.
 *
 * This is the one place in the app that scans without a hardware filter, which
 * is comparatively expensive - so it is strictly user-initiated, runs for a few
 * seconds, and stops itself. The address it finds is then handed to
 * [BleScanner] as a permanent hardware filter, so ongoing box tracking costs
 * nothing extra.
 */
class BleDiscovery(private val adapter: BluetoothAdapter?) {

    private val handler = Handler(Looper.getMainLooper())
    private var callback: ScanCallback? = null
    private val found = LinkedHashMap<String, MutableMap<String, Any?>>()

    @SuppressLint("MissingPermission")
    fun scan(durationMs: Long, onComplete: (List<Map<String, Any?>>) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || adapter.state != BluetoothAdapter.STATE_ON) {
            onComplete(emptyList())
            return
        }
        stop()
        found.clear()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                val address = device.address ?: return
                val name = runCatching { result.scanRecord?.deviceName ?: device.name }
                    .getOrNull()
                val entry = found.getOrPut(address) {
                    mutableMapOf("address" to address, "name" to name, "rssi" to result.rssi)
                }
                // Keep the strongest reading, and fill in a name if a later
                // packet carries one.
                val best = entry["rssi"] as? Int ?: result.rssi
                if (result.rssi > best) entry["rssi"] = result.rssi
                if (entry["name"] == null && name != null) entry["name"] = name
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "discovery scan failed: $errorCode")
            }
        }
        callback = cb

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        try {
            scanner.startScan(null, settings, cb)
        } catch (e: SecurityException) {
            Log.w(TAG, "missing BLUETOOTH_SCAN permission", e)
            onComplete(emptyList())
            return
        }

        handler.postDelayed({
            stop()
            onComplete(found.values.map { it.toMap() }.sortedByDescending { it["rssi"] as? Int ?: -127 })
        }, durationMs)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
    }

    companion object {
        private const val TAG = "BpDiscovery"
    }
}
