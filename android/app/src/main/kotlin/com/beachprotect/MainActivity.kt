package com.beachprotect

import com.beachprotect.bridge.GuardBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {

    private var bridge: GuardBridge? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        bridge = GuardBridge(
            applicationContext,
            flutterEngine.dartExecutor.binaryMessenger,
        ).also { it.attach() }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        bridge?.detach()
        bridge = null
        super.cleanUpFlutterEngine(flutterEngine)
    }
}
