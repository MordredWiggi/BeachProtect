package com.beachprotect.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import com.beachprotect.guard.AlarmReason
import com.beachprotect.guard.AlarmTarget
import kotlin.math.PI
import kotlin.math.sin

/**
 * Makes the noise.
 *
 * The siren is synthesised rather than shipped as an audio asset, which keeps
 * the APK small and, more usefully, means the waveform can be tuned freely: a
 * continuous-phase sweep between 700 Hz and 1500 Hz sits right in the region
 * where human hearing is most sensitive and cheap speakers are most efficient,
 * and it cuts through wind and surf far better than a single tone.
 *
 * ## Getting the sound out of the right speaker
 *
 * Routing is the fiddly part. Android sends `USAGE_ALARM` to the phone's own
 * loudspeaker, which is what we want on every phone - but it will *not*
 * reliably reach a Bluetooth speaker. Audio destined for the box therefore goes
 * out as `USAGE_MEDIA` with an explicit preferred device pinned to the A2DP
 * output, which is the only combination that lands on the box on every version
 * from Android 8 upwards.
 *
 * Both streams can run at once, and by default they do: the box alerts the
 * group back at the towel, while the phone screaming in the thief's hand is
 * what actually makes them put it down.
 */
class AlarmPlayer(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var localTrack: AudioTrack? = null
    private var boxTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var previousAlarmVolume = -1
    private var previousMusicVolume = -1
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    var playing: Boolean = false
        private set

    fun prepare() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
        }
    }

    // =====================================================================

    fun start(
        reason: AlarmReason,
        target: AlarmTarget,
        boxAddress: String?,
        volume: Float,
        vibrate: Boolean,
        speak: Boolean,
    ) {
        if (playing) return
        playing = true

        requestFocus()
        raiseVolumes()

        val boxDevice = boxAddress?.let { findA2dpDevice(it) }
        val effectiveTarget = if (boxDevice == null) AlarmTarget.PHONES_ONLY else target

        if (effectiveTarget != AlarmTarget.BOX_ONLY) {
            localTrack = buildTrack(
                usage = AudioAttributes.USAGE_ALARM,
                contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
                volume = volume,
                preferredDevice = null,
            )
        }
        if (effectiveTarget != AlarmTarget.PHONES_ONLY && boxDevice != null) {
            boxTrack = buildTrack(
                // USAGE_MEDIA is the only usage that reliably reaches A2DP.
                usage = AudioAttributes.USAGE_MEDIA,
                contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
                volume = volume,
                preferredDevice = boxDevice,
            )
        }

        localTrack?.playSafely()
        boxTrack?.playSafely()

        if (vibrate) startVibration()
        if (speak) speakReason(reason)
    }

    /**
     * A short, sharp double beep on this phone's own speaker.
     *
     * Played the instant a phone is lifted, well before the group siren. It
     * gives the owner immediate feedback that the guard noticed, and it tells a
     * thief straight away that the phone is protected - which is most of the
     * deterrent, and costs nothing if it turns out to be the owner.
     */
    fun playWarningChirp(volume: Float) {
        runCatching {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(chirpPcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(chirpPcm, 0, chirpPcm.size)
            track.setVolume(volume.coerceIn(0f, 1f))
            track.play()
            // Static tracks do not free themselves; release once it has played.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching {
                    track.stop()
                    track.release()
                }
            }, CHIRP_MS + 250L)
        }.onFailure { Log.w(TAG, "cannot play warning chirp", it) }
    }

    fun stop() {
        if (!playing) return
        playing = false

        listOf(localTrack, boxTrack).forEach { track ->
            runCatching {
                track?.pause()
                track?.flush()
                track?.stop()
                track?.release()
            }
        }
        localTrack = null
        boxTrack = null

        runCatching { vibrator()?.cancel() }
        runCatching { tts?.stop() }
        restoreVolumes()
        abandonFocus()
    }

    fun release() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    // =====================================================================
    // Audio plumbing
    // =====================================================================

    private fun AudioTrack.playSafely() {
        runCatching {
            setLoopPoints(0, SIREN_FRAMES - 1, -1)
            play()
        }.onFailure { Log.w(TAG, "cannot start siren track", it) }
    }

    private fun buildTrack(
        usage: Int,
        contentType: Int,
        volume: Float,
        preferredDevice: AudioDeviceInfo?,
    ): AudioTrack? = runCatching {
        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(sirenPcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(sirenPcm, 0, sirenPcm.size)
        track.setVolume(volume.coerceIn(0f, 1f))
        preferredDevice?.let { track.preferredDevice = it }
        track
    }.onFailure { Log.w(TAG, "cannot build siren track", it) }.getOrNull()

    private fun findA2dpDevice(address: String): AudioDeviceInfo? =
        runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                    it.address.equals(address, ignoreCase = true)
            } ?: audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                // Some OEMs report an empty address for A2DP sinks; if exactly
                // one is present it is unambiguously our box.
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        }.getOrNull()

    private fun requestFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val request = AudioFocusRequest
            .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = request
        runCatching { audioManager.requestAudioFocus(request) }
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private fun raiseVolumes() {
        runCatching {
            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            previousMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                0,
            )
        }
    }

    private fun restoreVolumes() {
        runCatching {
            if (previousAlarmVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
            }
            if (previousMusicVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousMusicVolume, 0)
            }
        }
        previousAlarmVolume = -1
        previousMusicVolume = -1
    }

    // =====================================================================
    // Haptics and speech
    // =====================================================================

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun startVibration() {
        val vibrator = vibrator() ?: return
        val pattern = longArrayOf(0, 400, 200, 400, 600)
        runCatching {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    private fun speakReason(reason: AlarmReason) {
        if (!ttsReady) return
        val phrase = when (reason) {
            AlarmReason.BOX_TAKEN -> "Attention. The speaker is being taken."
            AlarmReason.PICKUP_UNCONFIRMED -> "Attention. Put this phone down."
            AlarmReason.PEER_LOST -> "Attention. A phone is missing."
            AlarmReason.PANIC -> "Attention. Panic alarm."
            AlarmReason.TEST -> "This is a test alarm."
            else -> "Attention. Theft detected."
        }
        runCatching {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "bp-alarm")
        }
    }

    // =====================================================================
    // Siren synthesis
    // =====================================================================

    companion object {
        private const val TAG = "BpAlarmPlayer"
        private const val SAMPLE_RATE = 44_100

        /** Two seconds: two full up-down sweeps. */
        private const val SIREN_FRAMES = SAMPLE_RATE * 2

        private const val LOW_HZ = 700.0
        private const val HIGH_HZ = 1500.0

        /** Generated once per process and shared by both output tracks. */
        private val sirenPcm: ShortArray by lazy { synthesiseSiren() }

        private fun synthesiseSiren(): ShortArray {
            val out = ShortArray(SIREN_FRAMES)
            var phase = 0.0
            for (i in 0 until SIREN_FRAMES) {
                val t = i.toDouble() / SAMPLE_RATE
                // Smooth two-per-second sweep; a raised cosine avoids the harsh
                // discontinuity of a sawtooth sweep while staying attention
                // grabbing.
                val sweep = 0.5 - 0.5 * kotlin.math.cos(2 * PI * 2.0 * t)
                val frequency = LOW_HZ + (HIGH_HZ - LOW_HZ) * sweep
                phase += 2 * PI * frequency / SAMPLE_RATE
                if (phase > 2 * PI) phase -= 2 * PI

                // A little third harmonic gives small speakers something to
                // bite on; they reproduce 2 kHz far better than 700 Hz.
                val sample = 0.78 * sin(phase) + 0.22 * sin(3 * phase)

                // Short fades at each end keep the seamless loop click-free.
                val envelope = when {
                    i < FADE_FRAMES -> i.toDouble() / FADE_FRAMES
                    i > SIREN_FRAMES - FADE_FRAMES -> (SIREN_FRAMES - i).toDouble() / FADE_FRAMES
                    else -> 1.0
                }
                out[i] = (sample * envelope * Short.MAX_VALUE * 0.92).toInt().toShort()
            }
            return out
        }

        private const val FADE_FRAMES = 256

        /** Total length of the two-beep warning chirp. */
        const val CHIRP_MS = 420L

        private val chirpPcm: ShortArray by lazy { synthesiseChirp() }

        /** Two short 1800 Hz beeps - distinct from the siren's sweep. */
        private fun synthesiseChirp(): ShortArray {
            val frames = (SAMPLE_RATE * CHIRP_MS / 1000).toInt()
            val out = ShortArray(frames)
            val beep = frames / 5
            for (i in 0 until frames) {
                val slot = i / beep
                val inBeep = slot == 0 || slot == 2
                if (!inBeep) continue
                val local = i % beep
                val envelope = when {
                    local < FADE_FRAMES -> local.toDouble() / FADE_FRAMES
                    local > beep - FADE_FRAMES -> (beep - local).toDouble() / FADE_FRAMES
                    else -> 1.0
                }
                val sample = sin(2 * PI * 1800.0 * i / SAMPLE_RATE)
                out[i] = (sample * envelope * Short.MAX_VALUE * 0.75).toInt().toShort()
            }
            return out
        }
    }
}
