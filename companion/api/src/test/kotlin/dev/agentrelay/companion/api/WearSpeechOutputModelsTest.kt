package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WearSpeechOutputModelsTest {
    private val publicContent = WearSpeechOutputContent("Build completed", CompanionPrivacyClass.PUBLIC_SUMMARY)
    private val request = WearSpeechOutputRequest(
        requestId = "speech_output_v1_request01",
        generation = 2,
        content = publicContent,
        preferredTarget = WearSpeechOutputTarget.WATCH,
        createdAtEpochMillis = 100,
    )

    @Test
    fun `router respects explicit target and suppresses protected modes`() {
        val router = WearSpeechOutputRouter()
        val capabilities = listOf(
            WearSpeechOutputCapability(WearSpeechOutputTarget.PHONE, true, 80, true),
            WearSpeechOutputCapability(WearSpeechOutputTarget.WATCH, true, 70, true),
        )
        assertEquals(
            WearSpeechOutputDecision.Accepted(WearSpeechOutputTarget.WATCH),
            router.chooseTarget(request, capabilities, WearSpeechOutputMode.NORMAL, 2),
        )
        assertEquals(
            WearSpeechOutputRejection.MODE_SUPPRESSED,
            (
                router.chooseTarget(request, capabilities, WearSpeechOutputMode.THEATER, 2)
                    as WearSpeechOutputDecision.Rejected
                ).reason,
        )
    }

    @Test
    fun `router falls back to phone and rejects unavailable or low battery targets`() {
        val router = WearSpeechOutputRouter()
        val phone = WearSpeechOutputCapability(WearSpeechOutputTarget.PHONE, true, 60, true)
        val watch = WearSpeechOutputCapability(WearSpeechOutputTarget.WATCH, true, 10, true)
        assertEquals(
            WearSpeechOutputDecision.Accepted(WearSpeechOutputTarget.PHONE),
            router.chooseTarget(request, listOf(phone, watch), WearSpeechOutputMode.NORMAL, 2),
        )
        assertEquals(
            WearSpeechOutputRejection.NO_REACHABLE_TARGET,
            (
                router.chooseTarget(request, listOf(phone.copy(reachable = false)), WearSpeechOutputMode.NORMAL, 2)
                    as WearSpeechOutputDecision.Rejected
                ).reason,
        )
        assertEquals(
            WearSpeechOutputRejection.LOW_BATTERY,
            (
                router.chooseTarget(request, listOf(watch), WearSpeechOutputMode.NORMAL, 2)
                    as WearSpeechOutputDecision.Rejected
                ).reason,
        )
    }

    @Test
    fun `private content requires deliberate selection and generation`() {
        assertFailsWith<IllegalArgumentException> {
            WearSpeechOutputContent("private", CompanionPrivacyClass.PRIVATE_SUMMARY)
        }
        val privateRequest = request.copy(
            content = WearSpeechOutputContent("Selected result", CompanionPrivacyClass.PRIVATE_SUMMARY, true),
        )
        assertEquals(
            WearSpeechOutputDecision.Accepted(WearSpeechOutputTarget.WATCH),
            WearSpeechOutputRouter().chooseTarget(
                privateRequest,
                listOf(WearSpeechOutputCapability(WearSpeechOutputTarget.WATCH, true, 80, true)),
                WearSpeechOutputMode.NORMAL,
                2,
            ),
        )
        assertEquals(
            WearSpeechOutputRejection.STALE_GENERATION,
            (
                WearSpeechOutputRouter().chooseTarget(request, emptyList(), WearSpeechOutputMode.NORMAL, 3)
                    as WearSpeechOutputDecision.Rejected
                ).reason,
        )
    }

    @Test
    fun `session ignores stale callbacks and handles interruption and completion`() {
        val session = WearSpeechOutputSession()
        val accepted = WearSpeechOutputDecision.Accepted(WearSpeechOutputTarget.WATCH)
        assertTrue(session.start(request, accepted))
        assertFalse(session.interrupt(1, WearSpeechOutputInterruption.AUDIO_FOCUS_LOST))
        assertEquals(WearSpeechOutputState.PLAYING, session.state)
        assertTrue(session.interrupt(2, WearSpeechOutputInterruption.AUDIO_FOCUS_LOST))
        assertEquals(WearSpeechOutputState.INTERRUPTED, session.state)
        assertFalse(session.complete(2))
        assertFalse(session.start(request.copy(generation = 1), accepted))
    }
}
