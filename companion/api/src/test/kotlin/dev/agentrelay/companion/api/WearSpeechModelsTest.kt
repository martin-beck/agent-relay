package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WearSpeechModelsTest {
    private val capture = WearSpeechCapture(
        "speech_v1_capture-1", 1, "en-US", WearSpeechPermission.GRANTED,
        WearSpeechCaptureState.LISTENING, 100,
    )

    @Test
    fun captureRequiresPermissionAndTimesOutAtBound() {
        assertTrue(capture.timedOutAt(30_100))
        assertFalse(capture.timedOutAt(30_099))
        assertFailsWith<IllegalArgumentException> {
            capture.copy(permission = WearSpeechPermission.DENIED)
        }
    }

    @Test
    fun chunksAndReviewRejectOversizedOrUnreviewedInvalidContent() {
        assertFailsWith<IllegalArgumentException> {
            WearSpeechAudioChunk(capture.captureId, 1, 0, ByteArray(64 * 1024 + 1), true)
        }
        assertFailsWith<IllegalArgumentException> {
            WearSpeechTranscriptReview(capture.captureId, 1, "", null)
        }
        WearSpeechTranscriptReview(capture.captureId, 1, "hello", 92)
    }
}
