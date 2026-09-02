package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.SpeechOperationId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.min
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSpeechAudioPlaybackTest {
    @Test
    fun playbackWritesEveryPartialChunkAndReleasesItsFocusLease() = runTest {
        val factory = FakeOutputFactory(maxWriteSamples = 2)
        val focus = FakeFocusController()
        val playback = playback(factory, focus, StandardTestDispatcher(testScheduler))

        playback.play(operation(1), flowOf(frame(1, 2, 3, 4, 5)))

        val output = factory.outputs.single()
        assertEquals(1, output.startCalls)
        assertContentEquals(shortArrayOf(1, 2, 3, 4, 5), output.written.toShortArray())
        assertEquals(1, output.stopCalls)
        assertEquals(1, output.releaseCalls)
        assertEquals(1, focus.requests)
        assertEquals(1, focus.leases.single().abandonCalls)
        playback.close()
    }

    @Test
    fun emptyPlaybackDoesNotRequestFocusOrCreateAnAudioTrack() = runTest {
        val factory = FakeOutputFactory()
        val focus = FakeFocusController()
        val playback = playback(factory, focus, StandardTestDispatcher(testScheduler))

        playback.play(
            operation(1),
            kotlinx.coroutines.flow.emptyFlow(),
        )

        assertTrue(factory.outputs.isEmpty())
        assertEquals(0, focus.requests)
        playback.close()
    }

    @Test
    fun deniedFocusNeverCreatesAnAudioTrack() = runTest {
        val factory = FakeOutputFactory()
        val focus = FakeFocusController(granted = false)
        val playback = playback(factory, focus, StandardTestDispatcher(testScheduler))

        val failure = runCatching {
            playback.play(operation(1), flowOf(frame(1)))
        }.exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertTrue(factory.outputs.isEmpty())
        assertEquals(1, focus.requests)
        playback.close()
    }

    @Test
    fun changingPcmFormatFailsAndCleansTheOriginalOutput() = runTest {
        val factory = FakeOutputFactory()
        val focus = FakeFocusController()
        val playback = playback(factory, focus, StandardTestDispatcher(testScheduler))

        val failure = runCatching {
            playback.play(
                operation(1),
                flowOf(
                    frame(1, 2),
                    SpeechPcmFrame(SpeechPcmFormat(48_000, 2), shortArrayOf(3, 4)),
                ),
            )
        }.exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        assertEquals(1, factory.outputs.size)
        assertEquals(1, factory.outputs.single().releaseCalls)
        assertEquals(1, focus.leases.single().abandonCalls)
        playback.close()
    }

    @Test
    fun staleStopAndLateFocusLossCannotAffectAReplacementGeneration() = runTest {
        val firstOutput = FakeOutput(writeGate = CompletableDeferred())
        val secondOutput = FakeOutput(writeGate = CompletableDeferred())
        val factory = FakeOutputFactory(prebuilt = ArrayDeque(listOf(firstOutput, secondOutput)))
        val focus = FakeFocusController()
        val playback = playback(factory, focus, StandardTestDispatcher(testScheduler))

        val first = async {
            runCatching { playback.play(operation(1), flowOf(frame(1, 2))) }
        }
        runCurrent()
        playback.stop(operation(99))
        assertEquals(0, firstOutput.stopCalls)
        assertTrue(first.isActive)

        playback.stop(operation(1))
        runCurrent()
        assertIs<IllegalStateException>(first.await().exceptionOrNull())
        assertEquals(1, firstOutput.stopCalls)
        assertEquals(1, firstOutput.releaseCalls)
        assertEquals(1, focus.leases[0].abandonCalls)

        val second = async {
            runCatching { playback.play(operation(2), flowOf(frame(3, 4))) }
        }
        runCurrent()
        focus.callbacks[0]()
        assertFalse(secondOutput.stopped)
        assertTrue(second.isActive)

        playback.stop(operation(2))
        runCurrent()
        assertIs<IllegalStateException>(second.await().exceptionOrNull())
        assertEquals(1, secondOutput.stopCalls)
        assertEquals(1, secondOutput.releaseCalls)
        assertEquals(1, focus.leases[1].abandonCalls)
        playback.close()
    }

    @Test
    fun currentFocusLossStopsAndReleasesOnlyItsOwnOutput() = runTest {
        val output = FakeOutput(writeGate = CompletableDeferred())
        val factory = FakeOutputFactory(prebuilt = ArrayDeque(listOf(output)))
        val focus = FakeFocusController()
        val playback = playback(factory, focus, StandardTestDispatcher(testScheduler))

        val result = async {
            runCatching { playback.play(operation(1), flowOf(frame(5, 6))) }
        }
        runCurrent()
        focus.callbacks.single().invoke()
        runCurrent()

        assertIs<IllegalStateException>(result.await().exceptionOrNull())
        assertEquals(1, output.stopCalls)
        assertEquals(1, output.releaseCalls)
        assertEquals(1, focus.leases.single().abandonCalls)
        playback.close()
    }

    private fun playback(
        factory: FakeOutputFactory,
        focus: FakeFocusController,
        dispatcher: CoroutineDispatcher,
    ) = AndroidSpeechAudioPlayback(
        outputFactory = factory,
        focusController = focus,
        dispatcher = dispatcher,
    )

    private class FakeOutputFactory(
        private val maxWriteSamples: Int = Int.MAX_VALUE,
        private val prebuilt: ArrayDeque<FakeOutput> = ArrayDeque(),
    ) : PcmAudioOutputFactory {
        val outputs = mutableListOf<FakeOutput>()

        override fun create(format: SpeechPcmFormat): PcmAudioOutput {
            assertEquals(SpeechPcmFormat(16_000, 1), format)
            return (prebuilt.removeFirstOrNull() ?: FakeOutput(maxWriteSamples)).also {
                outputs += it
            }
        }
    }

    private class FakeOutput(
        private val maxWriteSamples: Int = Int.MAX_VALUE,
        private val writeGate: CompletableDeferred<Unit>? = null,
    ) : PcmAudioOutput {
        val written = mutableListOf<Short>()
        var startCalls = 0
        var stopCalls = 0
        var releaseCalls = 0
        var stopped = false
        private var released = false

        override fun start() {
            check(!released)
            startCalls += 1
        }

        override suspend fun write(
            samples: ShortArray,
            offset: Int,
            count: Int,
        ): Int {
            writeGate?.await()
            if (stopped) {
                return 0
            }
            val writtenCount = min(count, maxWriteSamples)
            repeat(writtenCount) { written += samples[offset + it] }
            return writtenCount
        }

        override fun stop() {
            if (!stopped) {
                stopped = true
                stopCalls += 1
                writeGate?.complete(Unit)
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

    private class FakeFocusController(
        private val granted: Boolean = true,
    ) : AudioFocusController {
        val callbacks = mutableListOf<() -> Unit>()
        val leases = mutableListOf<FakeFocusLease>()
        var requests = 0

        override fun request(onFocusLost: () -> Unit): AudioFocusLease? {
            requests += 1
            if (!granted) {
                return null
            }
            callbacks += onFocusLost
            return FakeFocusLease().also { leases += it }
        }
    }

    private class FakeFocusLease : AudioFocusLease {
        var abandonCalls = 0

        override fun abandon() {
            if (abandonCalls == 0) {
                abandonCalls = 1
            }
        }
    }

    private companion object {
        fun operation(value: Long) = SpeechOperationId(value)

        fun frame(vararg samples: Short) =
            SpeechPcmFrame(SpeechPcmFormat(16_000, 1), samples)
    }
}
