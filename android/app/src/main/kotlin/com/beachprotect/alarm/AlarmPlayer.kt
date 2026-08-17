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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Makes the noise.
 *
 * The siren is synthesised rather than shipped as an audio asset, which keeps
 * the APK small and lets the waveform be tuned freely: a continuous-phase sweep
 * between 700 Hz and 1500 Hz sits where human hearing is most sensitive and
 * cheap speakers are most efficient, and it cuts through wind and surf far
 * better than a single tone.
 *
 * ## Why streaming rather than a looped static buffer
 *
 * The obvious implementation is an `AudioTrack` in `MODE_STATIC` holding two
 * seconds of PCM with `setLoopPoints` doing the repetition. That is what this
 * class used to do, and it was unreliable: the static buffer is ~176 kB, which
 * some devices refuse outright, and `setLoopPoints` reports failure through a
 * return code rather than an exception, so a rejected loop looked like success
 * and produced silence. The failure mode was the worst possible one - the guard
 * believed it was screaming while the phone sat there mutely.
 *
 * `MODE_STREAM` with a small buffer and a writer thread has none of those
 * limits, runs indefinitely, and reports failure honestly. [sirenAudible] says
 * whether audio genuinely started, and it is surfaced in the app's diagnostics.
 *
 * ## Getting the sound out of the right speaker
 *
 * Android sends `USAGE_ALARM` to the phone's own loudspeaker, which is what we
 * want on every phone - but it will *not* reliably reach a Bluetooth speaker.
 * Audio destined for the box therefore goes out as `USAGE_MEDIA` with an
 * explicit preferred device pinned to the A2DP output, the only combination
 * that lands on the box on every version from Android 8 upwards.
 */
class AlarmPlayer(private val context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var localVoice: Voice? = null
    private var boxVoice: Voice? = null
    private var chirpVoice: Voice? = null
    private var focusRequest: AudioFocusRequest? = null
    private var previousAlarmVolume = -1
    private var previousMusicVolume = -1
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    var playing: Boolean = false
        private set

    /** True when at least one siren voice genuinely started producing audio. */
    var sirenAudible: Boolean = false
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
        sirenAudible = false

        requestFocus()
        raiseVolumes()

        val boxDevice = boxAddress?.let { findA2dpDevice(it) }
        val effectiveTarget = if (boxDevice == null) AlarmTarget.PHONES_ONLY else target

        if (effectiveTarget != AlarmTarget.BOX_ONLY) {
            localVoice = Voice(
                pcm = sirenPcm,
                usage = AudioAttributes.USAGE_ALARM,
                contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
                volume = volume,
                loop = true,
                preferredDevice = null,
            ).also { if (it.start()) sirenAudible = true }
        }
        if (effectiveTarget != AlarmTarget.PHONES_ONLY && boxDevice != null) {
            boxVoice = Voice(
                pcm = sirenPcm,
                // USAGE_MEDIA is the only usage that reliably reaches A2DP.
                usage = AudioAttributes.USAGE_MEDIA,
                contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
                volume = volume,
                loop = true,
                preferredDevice = boxDevice,
            ).also { if (it.start()) sirenAudible = true }
        }

        if (!sirenAudible) {
            Log.e(TAG, "SIREN FAILED TO START - no audio output could be opened")
        }

        if (vibrate) startVibration()
        if (speak) speakReason(reason)
    }

    /**
     * A short, sharp double beep on this phone's own speaker.
     *
     * Played the instant a phone is lifted, well before the group siren. It
     * gives the owner immediate feedback that the guard noticed, and tells a
     * thief straight away that the phone is protected - which is most of the
     * deterrent, and costs nothing if it turns out to be the owner.
     */
    fun playWarningChirp(volume: Float) {
        chirpVoice?.stop()
        chirpVoice = Voice(
            pcm = chirpPcm,
            usage = AudioAttributes.USAGE_ALARM,
            contentType = AudioAttributes.CONTENT_TYPE_SONIFICATION,
            volume = volume,
            loop = false,
            preferredDevice = null,
        ).also { it.start() }
    }

    fun stop() {
        chirpVoice?.stop()
        chirpVoice = null
        if (!playing) return
        playing = false
        sirenAudible = false

        localVoice?.stop()
        boxVoice?.stop()
        localVoice = null
        boxVoice = null

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
    // One output stream
    // =====================================================================

    /**
     * A single PCM stream, written from a daemon thread.
     *
     * `AudioTrack.write` blocks in stream mode until the data has been queued,
     * which paces the loop for us - no timers, no drift, and it stops promptly
     * when [stop] flips the flag.
     */
    private class Voice(
        private val pcm: ShortArray,
        private val usage: Int,
        private val contentType: Int,
        private val volume: Float,
        private val loop: Boolean,
        private val preferredDevice: AudioDeviceInfo?,
    ) {
        private var track: AudioTrack? = null

        @Volatile
        private var running = false
        private var thread: Thread? = null

        /** @return true when audio actually started. */
        fun start(): Boolean {
            val minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                Log.e(TAG, "no usable audio buffer size")
                return false
            }

            val built = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(contentType)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuffer * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.onFailure { Log.e(TAG, "cannot build audio track", it) }.getOrNull()
                ?: return false

            if (built.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "audio track did not initialise (state=${built.state})")
                runCatching { built.release() }
                return false
            }

            runCatching { built.setVolume(volume.coerceIn(0f, 1f)) }
            preferredDevice?.let { runCatching { built.preferredDevice = it } }

            val ok = runCatching {
                built.play()
                true
            }.onFailure { Log.e(TAG, "cannot start audio track", it) }.getOrDefault(false)
            if (!ok) {
                runCatching { built.release() }
                return false
            }

            track = built
            running = true
            thread = Thread({ pump(built) }, "bp-siren").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY - 1
                start()
            }
            return true
        }

        private fun pump(target: AudioTrack) {
            var offset = 0
            try {
                while (running) {
                    val chunk = minOf(CHUNK_FRAMES, pcm.size - offset)
                    val written = target.write(pcm, offset, chunk)
                    if (written < 0) {
                        Log.e(TAG, "audio write failed: $written")
                        break
                    }
                    offset += written
                    if (offset >= pcm.size) {
                        if (!loop) break
                        offset = 0
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "siren pump died", e)
            } finally {
                runCatching {
                    if (!loop) target.stop() else target.pause()
                }
            }
        }

        fun stop() {
            running = false
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            thread?.let { runCatching { it.join(400) } }
            thread = null
            runCatching { track?.stop() }
            runCatching { track?.release() }
            track = null
        }
    }

    // =====================================================================
    // Audio plumbing
    // =====================================================================

    private fun findA2dpDevice(address: String): AudioDeviceInfo? =
        runCatching {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                    it.address.equals(address, ignoreCase = true)
            } ?: outputs.firstOrNull {
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
            audioManager.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
        }
        // Separate block: on some devices raising the music stream throws under
        // Do Not Disturb, and that must not stop the alarm stream being raised.
        runCatching {
            previousMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
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
        }
        runCatching {
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
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0)) }
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
    // Synthesis
    // =====================================================================

    companion object {
        private const val TAG = "BpAlarmPlayer"
        private const val SAMPLE_RATE = 44_100

        /** Two seconds: two full up-down sweeps, looped seamlessly. */
        private const val SIREN_FRAMES = SAMPLE_RATE * 2

        private const val LOW_HZ = 700.0
        private const val HIGH_HZ = 1500.0
        private const val FADE_FRAMES = 256

        /** Written per blocking call; small enough to stop promptly. */
        private const val CHUNK_FRAMES = 2_048

        /** Total length of the two-beep warning chirp. */
        const val CHIRP_MS = 420L

        /** Generated once per process and shared by every voice. */
        private val sirenPcm: ShortArray by lazy { synthesiseSiren() }
        private val chirpPcm: ShortArray by lazy { synthesiseChirp() }

        private fun synthesiseSiren(): ShortArray {
            val out = ShortArray(SIREN_FRAMES)
            var phase = 0.0
            for (i in 0 until SIREN_FRAMES) {
                val t = i.toDouble() / SAMPLE_RATE
                // Smooth two-per-second sweep; a raised cosine avoids the harsh
                // discontinuity of a sawtooth while staying attention grabbing.
                val sweep = 0.5 - 0.5 * cos(2 * PI * 2.0 * t)
                val frequency = LOW_HZ + (HIGH_HZ - LOW_HZ) * sweep
                phase += 2 * PI * frequency / SAMPLE_RATE
                if (phase > 2 * PI) phase -= 2 * PI

                // A little third harmonic gives small speakers something to
                // bite on; they reproduce 2 kHz far better than 700 Hz.
                val sample = 0.78 * sin(phase) + 0.22 * sin(3 * phase)

                val envelope = when {
                    i < FADE_FRAMES -> i.toDouble() / FADE_FRAMES
                    i > SIREN_FRAMES - FADE_FRAMES -> (SIREN_FRAMES - i).toDouble() / FADE_FRAMES
                    else -> 1.0
                }
                out[i] = (sample * envelope * Short.MAX_VALUE * 0.92).toInt().toShort()
            }
            return out
        }

        /** Two short 1800 Hz beeps - distinct from the siren's sweep. */
        private fun synthesiseChirp(): ShortArray {
            val frames = (SAMPLE_RATE * CHIRP_MS / 1000).toInt()
            val out = ShortArray(frames)
            val beep = frames / 5
            for (i in 0 until frames) {
                val slot = i / beep
                if (slot != 0 && slot != 2) continue
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
