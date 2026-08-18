package com.beachprotect.service

/**
 * The command surface of [GuardService].
 *
 * Everything that can change the guard's behaviour goes through one of these,
 * whether it comes from the Flutter UI, the notification, the alarm screen or
 * the boot receiver. Having a single entry point means there is exactly one
 * place where the guard's state can change.
 */
object GuardIntents {

    private const val PREFIX = "com.beachprotect.action."

    /** Bring the foreground service up (does not arm by itself). */
    const val ACTION_START = PREFIX + "START"

    /** Tear the service down completely. */
    const val ACTION_STOP = PREFIX + "STOP"

    /** Arm this phone. */
    const val ACTION_ARM = PREFIX + "ARM"

    /**
     * Disarm this phone. If it is currently alarming, this also tells the rest
     * of the group to stop making noise - it is the owner saying "that was me".
     */
    const val ACTION_DISARM = PREFIX + "DISARM"

    /** Tell every phone in the group to disarm ("we are packing up"). */
    const val ACTION_DISARM_GROUP = PREFIX + "DISARM_GROUP"

    /** Tell every phone in the group to arm. */
    const val ACTION_ARM_GROUP = PREFIX + "ARM_GROUP"

    /**
     * Silence the sirens across the whole group but leave every phone guarding,
     * relearning baselines. The answer to a false alarm.
     *
     * Deliberately usable from a phone that is no longer alarming itself: whoever
     * has already silenced their own handset is exactly the person who needs to
     * reach the ones that have not.
     */
    const val ACTION_CLEAR_ALARM = PREFIX + "CLEAR_ALARM"

    /** Manual panic button. */
    const val ACTION_PANIC = PREFIX + "PANIC"

    /** Fire a harmless alarm so people can hear what it sounds like. */
    const val ACTION_TEST_ALARM = PREFIX + "TEST_ALARM"

    /** Settings changed underneath us; re-read the store. */
    const val ACTION_CONFIG_CHANGED = PREFIX + "CONFIG_CHANGED"

    /** Start a simulator scenario. Extra: [EXTRA_SCENARIO]. */
    const val ACTION_SIM_START = PREFIX + "SIM_START"

    /** Stop the simulator and drop all virtual peers. */
    const val ACTION_SIM_STOP = PREFIX + "SIM_STOP"

    /**
     * Raise the disarm surface after a short delay, so the user can lock the
     * phone and check that it really does appear over the lock screen.
     */
    const val ACTION_LOCKSCREEN_TEST = PREFIX + "LOCKSCREEN_TEST"

    /** Broadcast sent whenever the guard's user-visible state changes. */
    const val ACTION_GUARD_UPDATE = PREFIX + "GUARD_UPDATE"

    /**
     * Inexact AlarmManager safety net.
     *
     * The normal tick rides on scan results and sensor callbacks, both of which
     * wake the CPU on their own. This exists for the one case where neither
     * happens: a two-phone group whose only peer is switched off, leaving the
     * air completely silent. Without it, "the phone vanished" could go
     * unnoticed while the device dozes.
     */
    const val ACTION_HEARTBEAT = PREFIX + "HEARTBEAT"

    const val EXTRA_SCENARIO = "scenario"
    const val EXTRA_STATE = "state"
    const val EXTRA_REASON = "reason"
    const val EXTRA_SUBJECT_ID = "subjectId"
    const val EXTRA_SUBJECT_NAME = "subjectName"
    const val EXTRA_PENDING_REMAINING_MS = "pendingRemainingMs"
    const val EXTRA_SOURCE = "source"
}
