package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.SpeechOperationId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSpeechAudioCaptureTest {
    @Test
    fun missingPermissionNeverOpensTheMicrophone() = runTest {
        val factory = FakeRecorderFactory()
        val capture = AndroidSpeechAudioCapture(
            hasRecordPermission = { false },
            recorderFactory = factory,
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        val failure = runCatching {
            capture.capture(operation(1)).collect {}
        }.exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertTrue(factory.recorders.isEmpty())
        capture.close()
    }

    @Test
    fun captureEmitsOwnedPcmAndOnlyMatchingStopEndsTheGeneration() = runTest {
        val factory = FakeRecorderFactory()
        val capture = capture(factory, StandardTestDispatcher(testScheduler))
        val frames = mutableListOf<SpeechPcmFrame>()
        val collection = launch {
            capture.capture(operation(1)).collect { frames += it }
        }
        runCurrent()
        val recorder = factory.recorders.single()

        recorder.frames.send(shortArrayOf(1, 2, 3))
        runCurrent()
        capture.stop(operation(99))
        assertEquals(0, recorder.stopCalls)
        assertTrue(collection.isActive)

        capture.stop(operation(1))
        advanceUntilIdle()

        assertContentEquals(shortArrayOf(1, 2, 3), frames.single().samples)
        assertEquals(SpeechPcmFormat(16_000, 1), frames.single().format)
        assertEquals(1, recorder.startCalls)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
        assertTrue(collection.isCompleted)
        capture.close()
    }

    @Test
    fun stoppedUncollectedFlowCannotOpenOrReplaceANewerCapture() = runTest {
        val factory = FakeRecorderFactory()
        val capture = capture(factory, StandardTestDispatcher(testScheduler))
        val obsolete = capture.capture(operation(1))

        capture.stop(operation(1))
        val replacementFrames = mutableListOf<SpeechPcmFrame>()
        val replacement = launch {
            capture.capture(operation(2)).collect { replacementFrames += it }
        }
        runCurrent()
        val replacementRecorder = factory.recorders.single()

        obsolete.collect {}
        replacementRecorder.frames.send(shortArrayOf(7, 8))
        runCurrent()
        capture.stop(operation(2))
        advanceUntilIdle()

        assertEquals(1, factory.recorders.size)
        assertContentEquals(shortArrayOf(7, 8), replacementFrames.single().samples)
        assertEquals(1, replacementRecorder.releaseCalls)
        assertTrue(replacement.isCompleted)
        capture.close()
    }

    @Test
    fun closeStopsAndReleasesAnActiveMicrophoneExactlyOnce() = runTest {
        val factory = FakeRecorderFactory()
        val capture = capture(factory, StandardTestDispatcher(testScheduler))
        val collection = launch {
            runCatching { capture.capture(operation(1)).collect {} }
        }
        runCurrent()
        val recorder = factory.recorders.single()

        capture.close()
        advanceUntilIdle()
        capture.close()

        assertEquals(1, recorder.stopCalls)
        assertEquals(1, recorder.releaseCalls)
        assertTrue(collection.isCompleted)
        val failure = runCatching { capture.capture(operation(2)) }.exceptionOrNull()
        assertIs<IllegalStateException>(failure)
    }

    private fun capture(
        factory: FakeRecorderFactory,
        dispatcher: CoroutineDispatcher,
    ) = AndroidSpeechAudioCapture(
        hasRecordPermission = { true },
        recorderFactory = factory,
        dispatcher = dispatcher,
    )

    private class FakeRecorderFactory : PcmRecorderFactory {
        val recorders = mutableListOf<FakeRecorder>()

        override fun create(
            format: SpeechPcmFormat,
            requestedReadSamples: Int,
        ): PcmRecorder {
            assertEquals(SpeechPcmFormat(16_000, 1), format)
            assertEquals(1_600, requestedReadSamples)
            return FakeRecorder().also { recorders += it }
        }
    }

    private class FakeRecorder : PcmRecorder {
        val frames = Channel<ShortArray?>(Channel.UNLIMITED)
        var startCalls = 0
        var stopCalls = 0
        var releaseCalls = 0
        private var stopped = false
        private var released = false

        override fun start() {
            check(!released)
            startCalls += 1
        }

        override suspend fun read(destination: ShortArray): Int {
            val frame = frames.receive() ?: return 0
            frame.copyInto(destination)
            return frame.size
        }

        override fun stop() {
            if (!stopped) {
                stopped = true
                stopCalls += 1
                frames.trySend(null)
            }
        }

        override fun release() {
            if (!released) {
                released = true
                releaseCalls += 1
                stop()
            }
        }
    }

    private companion object {
        fun operation(value: Long) = SpeechOperationId(value)
    }
}
