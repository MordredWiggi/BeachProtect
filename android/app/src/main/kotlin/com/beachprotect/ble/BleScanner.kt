package com.beachprotect.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.beachprotect.guard.PowerProfile
import com.beachprotect.guard.RadioProfile

/**
 * Listens for group beacons, and optionally for the speaker box.
 *
 * ## Where the battery actually goes
 *
 * Scanning is by far the most expensive thing this app does, so three things
 * are true of every scan started here:
 *
 * 1. **It is always filtered.** A [ScanFilter] on our service UUID is pushed
 *    down into the Bluetooth controller, so the application processor is never
 *    woken for the hundreds of unrelated advertisements on a busy beach.
 *
 * 2. **It runs at the lowest duty cycle that still works.** `SCAN_MODE_LOW_POWER`
 *    is roughly a 512 ms window every 5.12 s. The scan only escalates to
 *    `SCAN_MODE_LOW_LATENCY` when the engine has actual evidence to chase, and
 *    drops straight back down afterwards.
 *
 * 3. **Restarts are throttled.** Android silently blocks an app that starts and
 *    stops scans more than five times in thirty seconds, and a blocked scanner
 *    is a guard that does not work. [MIN_RESTART_INTERVAL_MS] keeps us well
 *    under that ceiling, coalescing rapid profile changes.
 *
 * The box's BLE address, when known, is added as a *second* hardware filter
 * rather than forcing an unfiltered scan - so tracking the speaker costs
 * essentially nothing extra.
 *
 * The filter has to match the shape of what we actually broadcast, which is a
 * service-data field and nothing else. See [groupBeaconFilter]; getting that
 * wrong is invisible from one phone and fatal with two.
 */
class BleScanner(
    private val adapter: BluetoothAdapter?,
    private val listener: Listener,
) {

    interface Listener {
        /** A group beacon, already RSSI-stamped but not yet authenticated. */
        fun onServiceData(rssi: Int, payload: ByteArray, elapsedNanos: Long)

        /** An advertisement from the tracked speaker box. */
        fun onBoxAdvertisement(address: String, rssi: Int)

        fun onScanFailed(errorCode: Int)
    }

    private val handler = Handler(Looper.getMainLooper())

    private var currentRadio: RadioProfile? = null
    private var currentPower: PowerProfile? = null
    private var boxAddress: String? = null
    private var pendingRestart = false

    /**
     * When each of the last few scans was started.
     *
     * Android blocks an app that starts a scan more than five times in thirty
     * seconds, and a blocked scanner is a guard that does not work - so the old
     * code simply refused to restart more often than once every six seconds. That
     * is safe but too blunt: hearing a group command, or the first hint of a
     * theft, has to raise the duty cycle *now*, and waiting up to six seconds for
     * permission is several scan windows of the very thing we are trying to catch.
     *
     * Tracking the actual starts spends the allowance where it matters instead of
     * rationing it evenly. Escalations go straight through while there is room;
     * only when the allowance is genuinely nearly used up does anything wait.
     */
    private val recentStarts = ArrayDeque<Long>()

    var running: Boolean = false
        private set

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { dispatch(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { dispatch(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            running = false
            listener.onScanFailed(errorCode)
            // SCAN_FAILED_APPLICATION_REGISTRATION_FAILED happens when the
            // stack has been reset underneath us; a delayed retry recovers it.
            handler.postDelayed({ restartNow() }, RETRY_DELAY_MS)
        }
    }

    private fun dispatch(result: ScanResult) {
        val payload = result.scanRecord?.getServiceData(BleAdvertiser.SERVICE_PARCEL_UUID)
        if (payload != null) {
            listener.onServiceData(result.rssi, payload, result.timestampNanos)
            return
        }
        val address = result.device?.address ?: return
        if (address.equals(boxAddress, ignoreCase = true)) {
            listener.onBoxAdvertisement(address, result.rssi)
        }
    }

    /**
     * Applies a scan configuration, restarting the underlying scan only when
     * something meaningful changed and the throttle allows it.
     */
    fun apply(radio: RadioProfile, power: PowerProfile, boxBleAddress: String?) {
        val changed = radio != currentRadio || power != currentPower ||
            !boxBleAddress.equals(boxAddress, ignoreCase = true)
        currentRadio = radio
        currentPower = power
        boxAddress = boxBleAddress
        if (!changed && running) return

        val waitMs = if (running) delayBeforeNextStart() else 0L
        if (waitMs > 0) {
            // The allowance really is used up. Schedule one restart for when it
            // opens again; any further changes before then simply update the
            // fields above, so the scan that eventually starts is the current one.
            if (!pendingRestart) {
                pendingRestart = true
                handler.postDelayed({
                    pendingRestart = false
                    restartNow()
                }, waitMs)
            }
            return
        }
        restartNow()
    }

    /**
     * How long to wait before starting another scan, to stay under Android's
     * "five starts in thirty seconds" ceiling.
     *
     * One slot of the five is deliberately held back for the case that matters
     * most: a genuine escalation arriving right after a run of ordinary changes.
     */
    private fun delayBeforeNextStart(): Long {
        val now = SystemClock.elapsedRealtime()
        while (recentStarts.isNotEmpty() && now - recentStarts.first() > START_WINDOW_MS) {
            recentStarts.removeFirst()
        }
        if (recentStarts.size < MAX_STARTS_PER_WINDOW) return 0L
        return START_WINDOW_MS - (now - recentStarts.first()) + 100L
    }

    @SuppressLint("MissingPermission")
    private fun restartNow() {
        val scanner = adapter?.bluetoothLeScanner
        val radio = currentRadio ?: return
        val power = currentPower ?: PowerProfile.BALANCED
        if (scanner == null || adapter.state != BluetoothAdapter.STATE_ON) {
            running = false
            return
        }

        try {
            if (running) scanner.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot stop scan", e)
        }

        val filters = ArrayList<ScanFilter>(2)
        filters.add(groupBeaconFilter())
        boxAddress?.let { address ->
            if (BluetoothAdapter.checkBluetoothAddress(address)) {
                filters.add(ScanFilter.Builder().setDeviceAddress(address).build())
            }
        }

        val settings = buildSettings(radio, power)
        try {
            scanner.startScan(filters, settings, callback)
            running = true
            recentStarts.addLast(SystemClock.elapsedRealtime())
        } catch (e: SecurityException) {
            running = false
            Log.w(TAG, "missing BLUETOOTH_SCAN permission", e)
        } catch (e: IllegalStateException) {
            running = false
            Log.w(TAG, "bluetooth is off", e)
        }
    }

    /**
     * The filter that finds group members.
     *
     * It matches on **service data**, not on a service UUID, and that
     * distinction is the whole ballgame. Our beacon is a bare service-data
     * field (AD type 0x16) carrying the 20 byte payload — there is no
     * "list of service UUIDs" field in it, because that would cost four more
     * bytes out of the 31 a legacy advertisement gets and buy nothing.
     *
     * `ScanFilter.setServiceUuid` tests `ScanRecord.getServiceUuids()`, which
     * Android populates *only* from the service-UUID list AD types — never from
     * service data. So a service-UUID filter cannot match our advertisements at
     * all: `matchesServiceUuids` sees a null list and returns false for every
     * single packet. That filter was in place from the first commit, and it
     * meant no phone ever saw another phone. It is not a subtle degradation —
     * the mesh simply never formed, on any device, while both radios sat there
     * doing their jobs perfectly.
     *
     * The version byte is used as the pattern so the controller can drop
     * anything that is not ours before it ever wakes the CPU. Bytes beyond it
     * (group id, MAC) are deliberately left to the software path: they change
     * when the user joins a different group, and a filter that goes stale is a
     * guard that quietly stops seeing anybody.
     */
    private fun groupBeaconFilter(): ScanFilter = ScanFilter.Builder()
        .setServiceData(
            BleAdvertiser.SERVICE_PARCEL_UUID,
            byteArrayOf(Protocol.VERSION.toByte()),
            byteArrayOf(0xFF.toByte()),
        )
        .build()

    private fun buildSettings(radio: RadioProfile, power: PowerProfile): ScanSettings {
        val builder = ScanSettings.Builder()
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            // A phone being carried away gets *weaker*, which is exactly when we
            // must keep hearing it. Sticky matching would drop it.
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)

        // The calm duty cycle is not just an energy setting: it decides how long
        // a gap between two beacons is *normal*, and therefore how quickly the
        // group notices anything at all. LOW_POWER is a 512 ms window every
        // 5.12 s, which is sparse enough that on a two-phone group the default
        // profile spent its time flickering between "watched" and "no signal" and
        // missing half the group commands. Balanced is now genuinely balanced, and
        // the sparse setting is the one you have to choose.
        val scanMode = when (radio) {
            RadioProfile.ALERT, RadioProfile.CRITICAL -> ScanSettings.SCAN_MODE_LOW_LATENCY
            RadioProfile.CALM -> when (power) {
                PowerProfile.MAX_PROTECTION -> ScanSettings.SCAN_MODE_LOW_LATENCY
                PowerProfile.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                PowerProfile.ULTRA_SAVER -> ScanSettings.SCAN_MODE_LOW_POWER
            }
        }
        builder.setScanMode(scanMode)

        // Hardware batching lets the application processor stay asleep between
        // bursts. Only worth it while calm, and only where the chipset offers it.
        val batchingUseful = radio == RadioProfile.CALM &&
            power == PowerProfile.ULTRA_SAVER &&
            adapter?.isOffloadedScanBatchingSupported == true
        builder.setReportDelay(if (batchingUseful) BATCH_DELAY_MS else 0L)

        return builder.build()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        pendingRestart = false
        val scanner = adapter?.bluetoothLeScanner
        if (scanner != null && running) {
            try {
                scanner.stopScan(callback)
            } catch (e: SecurityException) {
                Log.w(TAG, "cannot stop scan", e)
            }
        }
        running = false
        currentRadio = null
        currentPower = null
    }

    companion object {
        private const val TAG = "BpScanner"

        /** Android's own limit: five scan starts inside this window gets you blocked. */
        const val START_WINDOW_MS = 30_000L

        /** ...so we use four and keep the fifth in reserve. */
        const val MAX_STARTS_PER_WINDOW = 4

        private const val RETRY_DELAY_MS = 4_000L
        private const val BATCH_DELAY_MS = 2_500L
    }
}
