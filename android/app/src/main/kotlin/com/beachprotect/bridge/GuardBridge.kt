package com.beachprotect.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import com.beachprotect.ble.BleDiscovery
import com.beachprotect.service.GuardIntents
import com.beachprotect.service.GuardService
import com.beachprotect.sim.Simulator
import com.beachprotect.store.GuardStore
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * The only seam between Flutter and the guard.
 *
 * Commands go one way over a [MethodChannel]; guard snapshots come back the
 * other way over an [EventChannel], pushed at whatever rate the service is
 * ticking at. The UI holds no authoritative state of its own - it renders what
 * the service says - which is what keeps the two halves from ever disagreeing
 * about whether the guard is armed.
 */
class GuardBridge(private val context: Context, messenger: BinaryMessenger) {

    private val store = GuardStore(context)
    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL)
    private val eventChannel = EventChannel(messenger, EVENT_CHANNEL)
    private val handler = Handler(Looper.getMainLooper())
    private var events: EventChannel.EventSink? = null

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val discovery by lazy { BleDiscovery(adapter) }

    fun attach() {
        methodChannel.setMethodCallHandler(::onMethodCall)
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) {
                events = sink
                GuardService.snapshotListener = { snapshot ->
                    handler.post { events?.success(snapshot) }
                }
                // Push one immediately so the UI is never blank on open.
                GuardService.instance?.let { service ->
                    handler.post { events?.success(service.currentSnapshotMap()) }
                }
            }

            override fun onCancel(arguments: Any?) {
                GuardService.snapshotListener = null
                events = null
            }
        })
    }

    fun detach() {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        GuardService.snapshotListener = null
        events = null
        discovery.stop()
    }

    // =====================================================================

    private fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            // ---- group ---------------------------------------------------
            "getSettings" -> result.success(store.toMap())

            "updateSettings" -> {
                @Suppress("UNCHECKED_CAST")
                val patch = call.arguments as? Map<String, Any?> ?: emptyMap()
                store.applyMap(patch)
                GuardService.send(context, GuardIntents.ACTION_CONFIG_CHANGED)
                result.success(store.toMap())
            }

            "createGroup" -> {
                val name = call.argument<String>("groupName").orEmpty()
                val myName = call.argument<String>("selfName").orEmpty()
                val code = store.createGroup(name)
                if (myName.isNotEmpty()) store.selfName = myName
                GuardService.send(context, GuardIntents.ACTION_CONFIG_CHANGED)
                result.success(code)
            }

            "joinGroup" -> {
                val code = call.argument<String>("code").orEmpty()
                val name = call.argument<String>("selfName").orEmpty()
                val ok = store.joinGroup(code, call.argument<String>("groupName").orEmpty())
                if (ok && name.isNotEmpty()) store.selfName = name
                if (ok) GuardService.send(context, GuardIntents.ACTION_CONFIG_CHANGED)
                result.success(ok)
            }

            // Handed to the service rather than done here, and answered only once
            // it is finished. Leaving used to be "stop the service, wipe the
            // store", which told the rest of the group nothing at all: the phone
            // simply went silent while its last beacon still claimed it was
            // guarding, which is precisely what a stolen phone looks like. The
            // store cannot be wiped first either — the farewell is signed with
            // the group key it is leaving.
            "leaveGroup" -> {
                val service = GuardService.instance
                if (service == null) {
                    store.leaveGroup()
                    result.success(true)
                } else {
                    service.leaveGroup { result.success(true) }
                }
            }

            "renamePeer" -> {
                val deviceId = call.argument<Int>("deviceId") ?: 0
                store.renamePeer(deviceId, call.argument<String>("name"))
                result.success(true)
            }

            "setPin" -> {
                store.setPin(call.argument<String>("pin").orEmpty())
                result.success(store.hasPin)
            }

            "checkPin" -> result.success(store.checkPin(call.argument<String>("pin").orEmpty()))

            // ---- guard control -------------------------------------------
            "startService" -> {
                GuardService.send(context, GuardIntents.ACTION_START)
                result.success(true)
            }

            "stopService" -> {
                GuardService.send(context, GuardIntents.ACTION_STOP)
                result.success(true)
            }

            "arm" -> command(GuardIntents.ACTION_ARM, result)
            "disarm" -> command(GuardIntents.ACTION_DISARM, result)
            "armGroup" -> command(GuardIntents.ACTION_ARM_GROUP, result)
            "disarmGroup" -> command(GuardIntents.ACTION_DISARM_GROUP, result)
            "clearAlarm" -> command(GuardIntents.ACTION_CLEAR_ALARM, result)
            "panic" -> command(GuardIntents.ACTION_PANIC, result)
            "testAlarm" -> command(GuardIntents.ACTION_TEST_ALARM, result)

            "getSnapshot" -> result.success(GuardService.instance?.currentSnapshotMap())

            // ---- box ------------------------------------------------------
            "pairedAudioDevices" -> result.success(
                GuardService.instance?.pairedAudioDevices() ?: emptyList<Map<String, Any?>>(),
            )

            "discoverBleDevices" -> {
                val duration = (call.argument<Int>("durationMs") ?: 6000).toLong()
                discovery.scan(duration) { devices -> result.success(devices) }
            }

            // ---- simulator -------------------------------------------------
            "simulationCatalogue" -> result.success(
                Simulator.catalogue().map {
                    mapOf(
                        "id" to it.id,
                        "title" to it.title,
                        "description" to it.description,
                        "durationMs" to it.durationMs,
                        "shouldAlarm" to it.shouldAlarm,
                        "budgetMs" to it.budgetMs,
                    )
                },
            )

            "startSimulation" -> {
                GuardService.send(context, GuardIntents.ACTION_SIM_START) {
                    it.putExtra(GuardIntents.EXTRA_SCENARIO, call.argument<String>("scenario"))
                }
                result.success(true)
            }

            "stopSimulation" -> command(GuardIntents.ACTION_SIM_STOP, result)

            "lockScreenTest" -> command(GuardIntents.ACTION_LOCKSCREEN_TEST, result)

            // ---- device / system -------------------------------------------
            "bluetoothEnabled" -> result.success(adapter?.state == BluetoothAdapter.STATE_ON)

            // The same test BleAdvertiser uses. Deliberately not
            // isMultipleAdvertisementSupported: that answers whether *several*
            // advertising sets can run at once, and we only ever run one -
            // several perfectly capable phones report false for it.
            "advertisingSupported" -> result.success(
                adapter?.bluetoothLeAdvertiser != null,
            )

            "requestEnableBluetooth" -> {
                runCatching {
                    context.startActivity(
                        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                result.success(true)
            }

            "isIgnoringBatteryOptimizations" -> {
                val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                result.success(power.isIgnoringBatteryOptimizations(context.packageName))
            }

            "requestIgnoreBatteryOptimizations" -> {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                result.success(true)
            }

            "canUseFullScreenIntent" -> {
                result.success(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        val manager = context.getSystemService(android.app.NotificationManager::class.java)
                        manager?.canUseFullScreenIntent() ?: false
                    } else {
                        true
                    },
                )
            }

            "openFullScreenIntentSettings" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                result.success(true)
            }

            "openAppSettings" -> {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                result.success(true)
            }

            else -> result.notImplemented()
        }
    }

    private fun command(action: String, result: MethodChannel.Result) {
        GuardService.send(context, action)
        result.success(true)
    }

    companion object {
        private const val METHOD_CHANNEL = "com.beachprotect/guard"
        private const val EVENT_CHANNEL = "com.beachprotect/guard_events"
    }
}
