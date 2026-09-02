package dev.agentrelay.speech.android

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

internal object AndroidPcmAudioOutputFactory : PcmAudioOutputFactory {
    override fun create(format: SpeechPcmFormat): PcmAudioOutput {
        val channelMask = when (format.channelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> error("Speech output channel count is unsupported")
        }
        val minimumBytes = AudioTrack.getMinBufferSize(
            format.sampleRateHz,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBytes > 0) { "Speech output configuration is unavailable" }
        val frameBytes = Math.multiplyExact(
            Math.multiplyExact(format.sampleRateHz / 10, format.channelCount),
            Short.SIZE_BYTES,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(SPEECH_AUDIO_ATTRIBUTES)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(format.sampleRateHz)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimumBytes, frameBytes))
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("Speech output initialization failed")
        }
        return AndroidPcmAudioOutput(track)
    }
}

internal class AndroidAudioFocusController(
    private val audioManager: AudioManager,
) : AudioFocusController {
    override fun request(onFocusLost: () -> Unit): AudioFocusLease? {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (
                change == AudioManager.AUDIOFOCUS_LOSS ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                onFocusLost()
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(SPEECH_AUDIO_ATTRIBUTES)
            .setOnAudioFocusChangeListener(listener)
            .setWillPauseWhenDucked(true)
            .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return null
        }
        val abandoned = AtomicBoolean(false)
        return AudioFocusLease {
            if (abandoned.compareAndSet(false, true)) {
                audioManager.abandonAudioFocusRequest(request)
            }
        }
    }
}

private class AndroidPcmAudioOutput(
    private val track: AudioTrack,
) : PcmAudioOutput {
    private val released = AtomicBoolean(false)
    private val stateMonitor = Any()
    private var started = false

    override fun start() {
        synchronized(stateMonitor) {
            check(!released.get()) { "Speech output is released" }
            if (!started) {
                track.play()
                check(track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    "Speech output did not start"
                }
                started = true
            }
        }
    }

    override suspend fun write(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): Int = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)

    override fun stop() {
        synchronized(stateMonitor) {
            stopLocked()
        }
    }

    override fun release() {
        if (released.compareAndSet(false, true)) {
            synchronized(stateMonitor) {
                stopLocked()
                track.release()
            }
        }
    }

    private fun stopLocked() {
        if (started) {
            runCatching { track.stop() }
            runCatching { track.flush() }
            started = false
        }
    }
}

internal val SPEECH_AUDIO_ATTRIBUTES: AudioAttributes =
    speechAudioAttributes()

private fun speechAudioAttributes(): AudioAttributes {
    val builder = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        builder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
    }
    return builder.build()
}
