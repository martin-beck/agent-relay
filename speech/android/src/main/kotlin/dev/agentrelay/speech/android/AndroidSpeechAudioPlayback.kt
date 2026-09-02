package dev.agentrelay.speech.android

import android.content.Context
import android.media.AudioManager
import dev.agentrelay.speech.api.SpeechOperationId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Generation-safe streaming PCM playback with a per-operation transient audio-focus lease.
 *
 * Focus is requested only when the first synthesized frame arrives. A stale stop or a late focus
 * callback can affect only the operation that created its output and focus request.
 */
class AndroidSpeechAudioPlayback internal constructor(
    private val outputFactory: PcmAudioOutputFactory,
    private val focusController: AudioFocusController,
    private val dispatcher: CoroutineDispatcher,
) : SpeechAudioPlayback {
    constructor(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        outputFactory = AndroidPcmAudioOutputFactory,
        focusController = AndroidAudioFocusController(
            checkNotNull(context.getSystemService(AudioManager::class.java)) {
                "Audio service is unavailable"
            },
        ),
        dispatcher = dispatcher,
    )

    private val stateMonitor = Any()
    private var activePlayback: ActivePlayback? = null
    private var closed = false

    override suspend fun play(
        operationId: SpeechOperationId,
        audio: Flow<SpeechPcmFrame>,
    ) = withContext(dispatcher) {
        val active = ActivePlayback(operationId)
        synchronized(stateMonitor) {
            check(!closed) { "Speech audio playback is closed" }
            check(activePlayback == null) { "A speech output is already active" }
            activePlayback = active
        }
        try {
            audio.collect { frame ->
                currentCoroutineContext().ensureActive()
                check(isCurrent(active)) { "Speech output was stopped" }
                val output = active.output ?: startOutput(active, frame.format)
                check(frame.format == active.format) { "Speech output format changed" }
                writeFrame(active, output, frame.samples)
            }
        } finally {
            finish(active)
        }
    }

    override suspend fun stop(operationId: SpeechOperationId) {
        val resources = synchronized(stateMonitor) {
            activePlayback?.takeIf { it.operationId == operationId }?.let {
                activePlayback = null
                it.stopped = true
                PlaybackResources(it.output, it.focusLease)
            }
        }
        resources?.shutdown()
    }

    override fun close() {
        val resources = synchronized(stateMonitor) {
            if (closed) {
                return
            }
            closed = true
            activePlayback?.also { it.stopped = true }?.let {
                PlaybackResources(it.output, it.focusLease)
            }.also {
                activePlayback = null
            }
        }
        resources?.shutdown()
    }

    private fun startOutput(
        active: ActivePlayback,
        format: SpeechPcmFormat,
    ): PcmAudioOutput {
        val focus = checkNotNull(
            focusController.request {
                onFocusLost(active)
            },
        ) { "Audio focus is unavailable" }
        var output: PcmAudioOutput? = null
        try {
            output = outputFactory.create(format)
            val started = synchronized(stateMonitor) {
                if (isCurrentLocked(active)) {
                    active.format = format
                    active.focusLease = focus
                    active.output = output
                    output.start()
                    true
                } else {
                    false
                }
            }
            check(started) { "Speech output was stopped" }
            return output
        } catch (failure: Throwable) {
            output?.shutdown()
            focus.abandon()
            throw failure
        }
    }

    private suspend fun writeFrame(
        active: ActivePlayback,
        output: PcmAudioOutput,
        samples: ShortArray,
    ) {
        var offset = 0
        while (offset < samples.size) {
            currentCoroutineContext().ensureActive()
            check(isCurrent(active)) { "Speech output was stopped" }
            val count = output.write(samples, offset, samples.size - offset)
            check(count in 1..(samples.size - offset)) { "Speech output write failed" }
            offset += count
        }
        check(isCurrent(active)) { "Speech output was stopped" }
    }

    private fun onFocusLost(active: ActivePlayback) {
        val resources = synchronized(stateMonitor) {
            if (activePlayback === active) {
                activePlayback = null
                active.stopped = true
                PlaybackResources(active.output, active.focusLease)
            } else {
                null
            }
        }
        resources?.shutdown()
    }

    private fun finish(active: ActivePlayback) {
        val resources = synchronized(stateMonitor) {
            if (activePlayback === active) {
                activePlayback = null
            }
            PlaybackResources(active.output, active.focusLease).also {
                active.output = null
                active.focusLease = null
            }
        }
        resources.shutdown()
    }

    private fun isCurrent(active: ActivePlayback): Boolean =
        synchronized(stateMonitor) { isCurrentLocked(active) }

    private fun isCurrentLocked(active: ActivePlayback): Boolean =
        !closed && !active.stopped && activePlayback === active

    private data class ActivePlayback(
        val operationId: SpeechOperationId,
        var format: SpeechPcmFormat? = null,
        var output: PcmAudioOutput? = null,
        var focusLease: AudioFocusLease? = null,
        var stopped: Boolean = false,
    )

    private data class PlaybackResources(
        val output: PcmAudioOutput?,
        val focusLease: AudioFocusLease?,
    ) {
        fun shutdown() {
            output?.shutdown()
            focusLease?.abandon()
        }
    }
}

internal fun interface PcmAudioOutputFactory {
    fun create(format: SpeechPcmFormat): PcmAudioOutput
}

internal interface PcmAudioOutput {
    fun start()

    suspend fun write(
        samples: ShortArray,
        offset: Int,
        count: Int,
    ): Int

    fun stop()

    fun release()
}

internal fun interface AudioFocusController {
    fun request(onFocusLost: () -> Unit): AudioFocusLease?
}

internal fun interface AudioFocusLease {
    fun abandon()
}

private fun PcmAudioOutput.stopSafely() {
    runCatching { stop() }
}

private fun PcmAudioOutput.shutdown() {
    stopSafely()
    runCatching { release() }
}
