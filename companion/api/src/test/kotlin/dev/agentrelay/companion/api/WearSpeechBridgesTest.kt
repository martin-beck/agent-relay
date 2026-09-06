package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WearSpeechBridgesTest {
    private val capture = WearSpeechCapture(
        "speech_v1_capture-1",
        1,
        "en-US",
        WearSpeechPermission.GRANTED,
        WearSpeechCaptureState.LISTENING,
        100,
    )
    private val chunk = WearSpeechAudioChunk(capture.captureId, capture.generation, 0, byteArrayOf(1), true)

    @Test
    fun inputRejectsPermissionDisconnectTimeoutEmptyAndCancellationBeforeAdapter() {
        var calls = 0
        val bridge = WearSpeechInputBridge(
            WearSpeechRecognizer { _, _ ->
                calls += 1
                WearSpeechRecognitionResult("hello", 90)
            },
        )
        fun request(
            value: WearSpeechCapture = capture,
            connected: Boolean = true,
            chunks: List<WearSpeechAudioChunk> = listOf(chunk),
            now: Long = 101,
        ) = WearSpeechInputRequest(value, chunks, connected, now)

        assertEquals(
            WearSpeechInputFailure.PERMISSION_DENIED,
            (
                bridge.transcribe(
                    request(
                        capture.copy(
                            permission = WearSpeechPermission.DENIED,
                            state = WearSpeechCaptureState.IDLE,
                        ),
                    ),
                )
                    as WearSpeechInputOutcome.Failed
                ).reason,
        )
        assertEquals(
            WearSpeechInputFailure.DEVICE_DISCONNECTED,
            (bridge.transcribe(request(connected = false)) as WearSpeechInputOutcome.Failed).reason,
        )
        assertEquals(
            WearSpeechInputFailure.TIMED_OUT,
            (bridge.transcribe(request(now = 30_100)) as WearSpeechInputOutcome.Failed).reason,
        )
        assertEquals(
            WearSpeechInputFailure.EMPTY_AUDIO,
            (bridge.transcribe(request(chunks = emptyList())) as WearSpeechInputOutcome.Failed).reason,
        )
        assertTrue(bridge.cancel(capture))
        assertEquals(
            WearSpeechInputFailure.CANCELLED,
            (bridge.transcribe(request()) as WearSpeechInputOutcome.Failed).reason,
        )
        assertEquals(0, calls)
    }

    @Test
    fun inputReturnsReviewAndLateAdapterFailureIsRedacted() {
        val bridge = WearSpeechInputBridge(
            WearSpeechRecognizer { _, _ ->
                WearSpeechRecognitionResult("hello", 90)
            },
        )
        val result = bridge.transcribe(WearSpeechInputRequest(capture, listOf(chunk), true, 101))
        assertEquals("hello", (result as WearSpeechInputOutcome.Review).value.transcript)
        val failed = WearSpeechInputBridge(WearSpeechRecognizer { _, _ -> error("native failure") })
            .transcribe(WearSpeechInputRequest(capture, listOf(chunk), true, 101))
        assertEquals(WearSpeechInputFailure.RECOGNITION_FAILED, (failed as WearSpeechInputOutcome.Failed).reason)
    }

    @Test
    fun outputSpeaksWhenCapableAndFallsBackOnlyToSafeText() {
        val request = WearSpeechOutputRequest(
            "speech_output_v1_request01",
            2,
            WearSpeechOutputContent("Build completed", CompanionPrivacyClass.PUBLIC_SUMMARY),
            WearSpeechOutputTarget.WATCH,
            100,
        )
        var spokenTarget: WearSpeechOutputTarget? = null
        val bridge = WearSpeechOutputBridge(
            synthesizer = WearSpeechSynthesizer { target, _ ->
                spokenTarget = target
                true
            },
        )
        val capabilities = listOf(WearSpeechOutputCapability(WearSpeechOutputTarget.WATCH, true, 80, true))
        assertEquals(
            WearSpeechOutputTarget.WATCH,
            (
                bridge.deliver(request, capabilities, WearSpeechOutputMode.NORMAL, 2)
                    as WearSpeechOutputOutcome.Spoken
                ).target,
        )
        assertEquals(WearSpeechOutputTarget.WATCH, spokenTarget)
        assertEquals(
            WearSpeechOutputRejection.MODE_SUPPRESSED,
            (
                bridge.deliver(request, capabilities, WearSpeechOutputMode.THEATER, 2)
                    as WearSpeechOutputOutcome.Fallback
                ).reason,
        )
    }

    @Test
    fun outputFailureAndPrivateFallbackRemainSafeAndCancellationIsGenerationScoped() {
        val privateRequest = WearSpeechOutputRequest(
            "speech_output_v1_private01",
            3,
            WearSpeechOutputContent("Private transcript", CompanionPrivacyClass.PRIVATE_SUMMARY, true),
            WearSpeechOutputTarget.WATCH,
            100,
        )
        val failing = WearSpeechOutputBridge(synthesizer = WearSpeechSynthesizer { _, _ -> false })
        val capabilities = listOf(WearSpeechOutputCapability(WearSpeechOutputTarget.WATCH, true, 80, true))
        val failed = failing.deliver(privateRequest, capabilities, WearSpeechOutputMode.NORMAL, 3)
            as WearSpeechOutputOutcome.Failed
        assertEquals(WearSpeechOutputFailure.SYNTHESIS_FAILED, failed.reason)
        assertEquals(null, failed.notificationText)
        val suppressed = failing.deliver(privateRequest, capabilities, WearSpeechOutputMode.THEATER, 3)
            as WearSpeechOutputOutcome.Fallback
        assertEquals(null, suppressed.notificationText)
        assertTrue(failing.cancel(privateRequest))
        assertEquals(
            WearSpeechOutputFailure.CANCELLED,
            (
                failing.deliver(privateRequest, capabilities, WearSpeechOutputMode.NORMAL, 3)
                    as WearSpeechOutputOutcome.Failed
                ).reason,
        )
    }
}
