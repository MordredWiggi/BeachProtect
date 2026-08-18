package com.beachprotect.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.beachprotect.R
import com.beachprotect.databinding.ActivityAlarmBinding
import com.beachprotect.guard.AlarmReason
import com.beachprotect.guard.DisarmMode
import com.beachprotect.guard.GuardState
import com.beachprotect.service.GuardIntents
import com.beachprotect.store.GuardStore
import java.util.concurrent.Executor

/**
 * The screen the owner sees when their phone is picked up, and the screen the
 * whole group sees when something is being stolen.
 *
 * Written as a plain Android activity rather than a Flutter route on purpose:
 * it must be able to light up over the lock screen in a few hundred
 * milliseconds, and it must work when the Flutter engine has long since been
 * torn down to save battery.
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmBinding
    private lateinit var store: GuardStore

    private var mode: GuardState = GuardState.ALARM
    private var reason: AlarmReason? = null
    private var subjectName: String? = null
    private var countdown: CountDownTimer? = null
    private var enteredPin = StringBuilder()
    private var pinVisible = false

    /**
     * What a successful authentication will actually do.
     *
     * There are two quite different things somebody might want out of this
     * screen, and the field test showed what happens when only one is offered.
     * Silencing a group alarm used to *disarm the phone it was pressed on* while
     * telling the others to keep guarding, so the group came out of every
     * incident half armed, half not, and the phone that had just been silenced
     * lost the buttons that could reach the ones still screaming. Now the default
     * during an alarm is "stop everybody, keep everybody guarding" - because
     * nearly every alarm somebody stands in front of is a false one - and
     * standing the group down is a separate, deliberate choice.
     */
    private var pendingAction: String = GuardIntents.ACTION_DISARM

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != GuardIntents.ACTION_GUARD_UPDATE) return
            applyUpdate(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        store = GuardStore(this)
        binding = ActivityAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wireKeypad()
        binding.disarmButton.setOnClickListener {
            pendingAction = primaryAction()
            onDisarmPressed()
        }
        binding.disarmGroupButton.setOnClickListener {
            pendingAction = GuardIntents.ACTION_DISARM_GROUP
            onDisarmPressed()
        }
        binding.biometricButton.setOnClickListener { promptBiometric() }

        applyUpdate(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyUpdate(intent)
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            updateReceiver,
            IntentFilter(GuardIntents.ACTION_GUARD_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(updateReceiver) }
    }

    override fun onDestroy() {
        countdown?.cancel()
        super.onDestroy()
    }

    /**
     * The back button must not dismiss an alarm - that would make the whole
     * thing pointless. Disarming is the only way out.
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Intentionally ignored.
    }

    // =====================================================================
    // Presentation
    // =====================================================================

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun applyUpdate(intent: Intent?) {
        val stateName = intent?.getStringExtra(GuardIntents.EXTRA_STATE)
        val nextMode = runCatching { GuardState.valueOf(stateName ?: "") }
            .getOrDefault(GuardState.ALARM)

        // The service tells us when the incident is over.
        if (nextMode == GuardState.DISARMED || nextMode == GuardState.ARMED ||
            nextMode == GuardState.CALIBRATING
        ) {
            finishAndRemoveTask()
            return
        }

        mode = nextMode
        pendingAction = primaryAction()
        reason = runCatching {
            AlarmReason.valueOf(intent?.getStringExtra(GuardIntents.EXTRA_REASON) ?: "")
        }.getOrNull()
        subjectName = intent?.getStringExtra(GuardIntents.EXTRA_SUBJECT_NAME)

        renderChrome()

        val remaining = intent?.getLongExtra(GuardIntents.EXTRA_PENDING_REMAINING_MS, 0L) ?: 0L
        if (mode == GuardState.PENDING && remaining > 0) {
            startCountdown(remaining)
        } else {
            countdown?.cancel()
            binding.countdownText.visibility = View.GONE
        }

        configureAuthUi()
    }

    /**
     * The button people press without reading.
     *
     * PENDING is this phone's own grace period - the owner has picked their phone
     * up and wants it to stop guarding, and nothing has been announced to the
     * group yet, so there is nothing to call off. An ALARM has been announced,
     * and the useful thing is to call it off everywhere while leaving everyone's
     * things protected.
     */
    private fun primaryAction(): String = if (mode == GuardState.PENDING) {
        GuardIntents.ACTION_DISARM
    } else {
        GuardIntents.ACTION_CLEAR_ALARM
    }

    private fun renderChrome() {
        val pending = mode == GuardState.PENDING
        val background = if (pending) R.color.bp_pending_deep else R.color.bp_alarm_deep
        binding.alarmRoot.setBackgroundColor(ContextCompat.getColor(this, background))

        binding.disarmButton.setText(if (pending) R.string.disarm else R.string.stop_alarm)
        binding.disarmHint.visibility = if (pending) View.GONE else View.VISIBLE
        binding.disarmGroupButton.visibility = if (pending) View.GONE else View.VISIBLE

        binding.alarmTitle.setText(
            when {
                pending -> R.string.alarm_title_pickup
                reason == AlarmReason.BOX_TAKEN -> R.string.alarm_title_box
                reason == AlarmReason.PEER_LOST -> R.string.alarm_title_lost
                reason == AlarmReason.PANIC -> R.string.alarm_title_panic
                reason == AlarmReason.TEST -> R.string.alarm_title_test
                else -> R.string.alarm_title_theft
            },
        )

        binding.alarmSubtitle.setText(
            if (pending) R.string.alarm_subtitle_pending else R.string.alarm_subtitle_alarm,
        )

        binding.detailText.text = when {
            pending -> getString(R.string.alarm_subtitle_pending)
            reason == AlarmReason.BOX_TAKEN ->
                store.boxName?.let { "The speaker \"$it\" left the group" } ?: ""
            subjectName != null -> "Device: $subjectName"
            else -> ""
        }
        binding.detailText.visibility =
            if (binding.detailText.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun startCountdown(remainingMs: Long) {
        countdown?.cancel()
        binding.countdownText.visibility = View.VISIBLE
        countdown = object : CountDownTimer(remainingMs, 250) {
            override fun onTick(millisUntilFinished: Long) {
                binding.countdownText.text = ((millisUntilFinished + 999) / 1000).toString()
            }

            override fun onFinish() {
                binding.countdownText.text = "0"
            }
        }.also { it.start() }
    }

    // =====================================================================
    // Authentication
    // =====================================================================

    private fun configureAuthUi() {
        when (store.disarmMode) {
            DisarmMode.CONFIRM_TAP -> {
                setPinVisible(false)
                binding.biometricButton.visibility = View.GONE
            }

            DisarmMode.PIN_ONLY -> {
                setPinVisible(store.hasPin)
                binding.biometricButton.visibility = View.GONE
            }

            DisarmMode.BIOMETRIC_WITH_PIN -> {
                setPinVisible(false)
                binding.biometricButton.visibility =
                    if (store.hasPin && biometricAvailable()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setPinVisible(visible: Boolean) {
        pinVisible = visible
        binding.pinDisplay.visibility = if (visible) View.VISIBLE else View.GONE
        binding.keypad.visibility = if (visible) View.VISIBLE else View.GONE
        binding.pinError.visibility = View.INVISIBLE
        enteredPin.setLength(0)
        renderPin()
    }

    private fun renderPin() {
        binding.pinDisplay.text = buildString {
            repeat(enteredPin.length) { append("*") }
            repeat((MIN_PIN_LENGTH - enteredPin.length).coerceAtLeast(0)) { append("-") }
        }
    }

    private fun wireKeypad() {
        val digits = listOf(
            binding.key0 to "0", binding.key1 to "1", binding.key2 to "2",
            binding.key3 to "3", binding.key4 to "4", binding.key5 to "5",
            binding.key6 to "6", binding.key7 to "7", binding.key8 to "8",
            binding.key9 to "9",
        )
        digits.forEach { (view, digit) ->
            view.setOnClickListener {
                if (enteredPin.length < MAX_PIN_LENGTH) {
                    enteredPin.append(digit)
                    binding.pinError.visibility = View.INVISIBLE
                    renderPin()
                    // Most people use the standard length, so check as they go
                    // and let them straight through when it matches.
                    if (enteredPin.length >= MIN_PIN_LENGTH && store.checkPin(enteredPin.toString())) {
                        succeed()
                    }
                }
            }
        }
        binding.keyDelete.setOnClickListener {
            if (enteredPin.isNotEmpty()) enteredPin.setLength(enteredPin.length - 1)
            renderPin()
        }
        binding.keyOk.setOnClickListener { submitPin() }
    }

    private fun submitPin() {
        if (store.checkPin(enteredPin.toString())) {
            succeed()
        } else {
            binding.pinError.visibility = View.VISIBLE
            enteredPin.setLength(0)
            renderPin()
        }
    }

    private fun onDisarmPressed() {
        when (store.disarmMode) {
            DisarmMode.CONFIRM_TAP -> succeed()

            DisarmMode.PIN_ONLY -> {
                if (!store.hasPin) {
                    // Misconfigured: never trap the owner out of their own phone.
                    succeed()
                } else if (!pinVisible) {
                    setPinVisible(true)
                } else {
                    submitPin()
                }
            }

            DisarmMode.BIOMETRIC_WITH_PIN -> {
                if (pinVisible) submitPin() else promptBiometric()
            }
        }
    }

    private fun biometricAvailable(): Boolean {
        val manager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK
        return manager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun promptBiometric() {
        if (!biometricAvailable()) {
            // No usable biometric hardware: fall back to the group PIN, or let
            // a plain tap through if no PIN was ever configured.
            if (store.hasPin) setPinVisible(true) else succeed()
            return
        }

        val executor: Executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this as FragmentActivity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    succeed()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelled or unavailable: offer the PIN instead of giving up.
                    if (store.hasPin) setPinVisible(true)
                }
            },
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setNegativeButtonText(getString(R.string.enter_pin))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    private fun succeed() {
        countdown?.cancel()
        val intent = Intent(this, com.beachprotect.service.GuardService::class.java).apply {
            action = pendingAction
            putExtra(GuardIntents.EXTRA_SOURCE, "alarm_screen")
        }
        ContextCompat.startForegroundService(this, intent)
        finishAndRemoveTask()
    }

    companion object {
        private const val MIN_PIN_LENGTH = 4
        private const val MAX_PIN_LENGTH = 8

        /** Builds the intent the service uses to raise this screen. */
        fun intentFor(
            context: Context,
            state: GuardState,
            reason: AlarmReason?,
            subjectId: Int,
            subjectName: String?,
            pendingRemainingMs: Long,
        ): Intent = Intent(context, AlarmActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION,
            )
            putExtra(GuardIntents.EXTRA_STATE, state.name)
            putExtra(GuardIntents.EXTRA_REASON, reason?.name)
            putExtra(GuardIntents.EXTRA_SUBJECT_ID, subjectId)
            putExtra(GuardIntents.EXTRA_SUBJECT_NAME, subjectName)
            putExtra(GuardIntents.EXTRA_PENDING_REMAINING_MS, pendingRemainingMs)
        }
    }
}
