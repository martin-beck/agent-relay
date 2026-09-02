package dev.agentrelay.speech.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.agentrelay.speech.api.SpeechOperationId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Generation-safe 16 kHz mono microphone capture for offline recognition.
 *
 * Runtime permission prompting remains an app/UI responsibility. This boundary checks the
 * permission immediately before opening the microphone and never exposes platform error details.
 */
class AndroidSpeechAudioCapture internal constructor(
    private val hasRecordPermission: () -> Boolean,
    private val recorderFactory: PcmRecorderFactory,
    private val dispatcher: CoroutineDispatcher,
) : SpeechAudioCapture {
    constructor(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        hasRecordPermission = {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        },
        recorderFactory = AndroidPcmRecorderFactory,
        dispatcher = dispatcher,
    )

    private val stateMonitor = Any()
    private var activeCapture: ActiveCapture? = null
    private var closed = false

    override fun capture(operationId: SpeechOperationId): Flow<SpeechPcmFrame> {
        val active = ActiveCapture(operationId)
        synchronized(stateMonitor) {
            check(!closed) { "Speech audio capture is closed" }
            check(activeCapture == null) { "A microphone capture is already active" }
            activeCapture = active
        }
        return flow { runCapture(active) }.flowOn(dispatcher)
    }

    override suspend fun stop(operationId: SpeechOperationId) {
        val recorder = synchronized(stateMonitor) {
            activeCapture?.takeIf { it.operationId == operationId }?.let {
                activeCapture = null
                it.stopped = true
                it.recorder
            }
        }
        recorder?.stopSafely()
    }

    override fun close() {
        val recorder = synchronized(stateMonitor) {
            if (closed) {
                return
            }
            closed = true
            activeCapture?.also { it.stopped = true }?.recorder.also {
                activeCapture = null
            }
        }
        recorder?.shutdown()
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<SpeechPcmFrame>.runCapture(
        active: ActiveCapture,
    ) {
        var recorder: PcmRecorder? = null
        val samples = ShortArray(READ_SAMPLES)
        try {
            check(hasRecordPermission()) { "Microphone permission is required" }
            if (!isCurrent(active)) {
                return
            }
            recorder = recorderFactory.create(CAPTURE_FORMAT, READ_SAMPLES)
            val started = synchronized(stateMonitor) {
                if (isCurrentLocked(active)) {
                    active.recorder = recorder
                    recorder.start()
                    true
                } else {
                    false
                }
            }
            if (!started) {
                return
            }
            while (isCurrent(active)) {
                currentCoroutineContext().ensureActive()
                val count = recorder.read(samples)
                check(count <= samples.size) { "Microphone returned an oversized frame" }
                if (count > 0) {
                    emit(SpeechPcmFrame(CAPTURE_FORMAT, samples.copyOf(count)))
                } else {
                    check(!isCurrent(active)) { "Microphone capture stopped unexpectedly" }
                }
            }
        } finally {
            samples.fill(0)
            synchronized(stateMonitor) {
                if (activeCapture === active) {
                    activeCapture = null
                }
                active.recorder = null
            }
            recorder?.shutdown()
        }
    }

    private fun isCurrent(active: ActiveCapture): Boolean =
        synchronized(stateMonitor) { isCurrentLocked(active) }

    private fun isCurrentLocked(active: ActiveCapture): Boolean =
        !closed && !active.stopped && activeCapture === active

    private data class ActiveCapture(
        val operationId: SpeechOperationId,
        var recorder: PcmRecorder? = null,
        var stopped: Boolean = false,
    )

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val READS_PER_SECOND = 10
        const val READ_SAMPLES = SAMPLE_RATE_HZ / READS_PER_SECOND
        val CAPTURE_FORMAT = SpeechPcmFormat(SAMPLE_RATE_HZ, channelCount = 1)
    }
}

internal fun interface PcmRecorderFactory {
    fun create(
        format: SpeechPcmFormat,
        requestedReadSamples: Int,
    ): PcmRecorder
}

internal interface PcmRecorder {
    fun start()

    suspend fun read(destination: ShortArray): Int

    fun stop()

    fun release()
}

internal object AndroidPcmRecorderFactory : PcmRecorderFactory {
    @SuppressLint("MissingPermission")
    override fun create(
        format: SpeechPcmFormat,
        requestedReadSamples: Int,
    ): PcmRecorder {
        check(format.channelCount == 1) { "Microphone capture must be mono" }
        val minimumBytes = AudioRecord.getMinBufferSize(
            format.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimumBytes > 0) { "Microphone configuration is unavailable" }
        val requestedBytes = Math.multiplyExact(requestedReadSamples, Short.SIZE_BYTES)
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(format.sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(minimumBytes, requestedBytes))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("Microphone initialization failed")
        }
        return AndroidPcmRecorder(record)
    }
}

private class AndroidPcmRecorder(
    private val record: AudioRecord,
) : PcmRecorder {
    private val released = AtomicBoolean(false)
    private val stateMonitor = Any()
    private var started = false

    @SuppressLint("MissingPermission")
    override fun start() {
        synchronized(stateMonitor) {
            check(!released.get()) { "Microphone recorder is released" }
            if (!started) {
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Microphone did not start"
                }
                started = true
            }
        }
    }

    override suspend fun read(destination: ShortArray): Int =
        record.read(destination, 0, destination.size, AudioRecord.READ_BLOCKING)

    override fun stop() {
        synchronized(stateMonitor) {
            stopLocked()
        }
    }

    override fun release() {
        if (released.compareAndSet(false, true)) {
            synchronized(stateMonitor) {
                stopLocked()
                record.release()
            }
        }
    }

    private fun stopLocked() {
        if (started) {
            runCatching { record.stop() }
            started = false
        }
    }
}

private fun PcmRecorder.stopSafely() {
    runCatching { stop() }
}

private fun PcmRecorder.shutdown() {
    stopSafely()
    runCatching { release() }
}
