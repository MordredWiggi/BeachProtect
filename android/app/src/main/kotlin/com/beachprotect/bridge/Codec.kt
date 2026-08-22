package com.beachprotect.bridge

import com.beachprotect.guard.GuardSnapshot
import com.beachprotect.guard.PeerSnapshot
import com.beachprotect.guard.Proximity

/**
 * Turns engine snapshots into the plain maps the Flutter side consumes.
 *
 * Doubles are sanitised here rather than in Dart: RSSI values start life as
 * NaN before a peer's filter has converged, and NaN does not survive the
 * standard message codec cleanly.
 */
object Codec {

    /**
     * @param localNames nicknames the user typed, which always win.
     * @param learnedNames names peers broadcast about themselves, persisted so a
     *        peer that has gone quiet does not fall back to a hexadecimal id.
     */
    fun snapshot(
        snapshot: GuardSnapshot,
        localNames: Map<Int, String>,
        learnedNames: Map<Int, String> = emptyMap(),
        diagnostics: Map<String, Any?>,
    ): Map<String, Any?> = mapOf(
        "state" to snapshot.state.name,
        "radioProfile" to snapshot.radioProfile.name,
        "selfDeviceId" to snapshot.selfDeviceId,
        "selfName" to snapshot.selfName,
        "selfStationary" to snapshot.selfStationary,
        "selfMotionScore" to snapshot.selfMotionScore,
        "pickupArmed" to snapshot.pickupArmed,
        "pickupArmsInMs" to snapshot.pickupArmsInMs,
        "pendingRemainingMs" to snapshot.pendingRemainingMs,
        "alarmReason" to snapshot.alarmReason?.name,
        "alarmSubjectId" to snapshot.alarmSubjectId,
        "alarmSubjectName" to nameFor(snapshot, localNames, learnedNames, snapshot.alarmSubjectId),
        "groupAlarmActive" to snapshot.groupAlarmActive,
        "stopPending" to snapshot.stopPending,
        "stopConfirmed" to snapshot.stopConfirmed,
        "stopExpected" to snapshot.stopExpected,
        "peers" to snapshot.peers.map { peer(it, localNames, learnedNames) },
        "box" to mapOf(
            "configured" to snapshot.box.configured,
            "name" to snapshot.box.name,
            "address" to snapshot.box.address,
            "audioLinkConnected" to snapshot.box.audioLinkConnected,
            "bleRssi" to clean(snapshot.box.bleRssi),
            "bleProximity" to snapshot.box.bleProximity.name,
            "bleTracked" to snapshot.box.bleTracked,
            "guardedByThisPhone" to snapshot.box.guardedByThisPhone,
        ),
        "warnings" to snapshot.warnings.map { it.name },
        "diagnostics" to diagnostics,
    )

    private fun peer(
        peer: PeerSnapshot,
        localNames: Map<Int, String>,
        learnedNames: Map<Int, String>,
    ): Map<String, Any?> = mapOf(
        "deviceId" to peer.deviceId,
        "name" to (localNames[peer.deviceId] ?: peer.name ?: learnedNames[peer.deviceId]),
        "broadcastName" to peer.name,
        "rssi" to clean(peer.rssi),
        "baseline" to clean(peer.baseline),
        "dropDb" to clean(peer.dropDb),
        "slopeDbPerSecond" to clean(peer.slopeDbPerSecond),
        "proximity" to peer.proximity.name,
        "estimatedMetres" to clean(peer.estimatedMetres),
        "battery" to peer.battery,
        "armed" to peer.armed,
        "alarming" to peer.alarming,
        "stationary" to peer.stationary,
        "motionScore" to peer.motionScore,
        "boxGuardian" to peer.boxGuardian,
        "simulated" to peer.simulated,
        "lastSeenMsAgo" to peer.lastSeenMsAgo,
        "presence" to peer.presence.name,
        "staleAfterMs" to peer.staleAfterMs,
        "suspected" to peer.suspected,
        "votesAgainst" to peer.votesAgainst,
        "votesRequired" to peer.votesRequired,
    )

    private fun nameFor(
        snapshot: GuardSnapshot,
        localNames: Map<Int, String>,
        learnedNames: Map<Int, String>,
        deviceId: Int,
    ): String? {
        if (deviceId == snapshot.selfDeviceId) return snapshot.selfName.ifEmpty { "This phone" }
        localNames[deviceId]?.let { return it }
        snapshot.peers.firstOrNull { it.deviceId == deviceId }?.name?.let { return it }
        return learnedNames[deviceId]
    }

    /** NaN and infinities become null so the Dart side can pattern match on it. */
    private fun clean(value: Double): Double? =
        if (value.isNaN() || value.isInfinite()) null else value

    fun proximityLabel(proximity: Proximity): String = when (proximity) {
        Proximity.UNKNOWN -> "Unknown"
        Proximity.HERE -> "Right here"
        Proximity.CLOSE -> "Close"
        Proximity.NEARBY -> "Nearby"
        Proximity.FAR -> "Far"
        Proximity.VERY_FAR -> "Very far"
    }
}
