package com.example.agentrelay.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPermissionLauncherTest {
    @Test
    fun permissionResultCanStartOnlyTheCurrentlySelectedSession() {
        assertTrue(isCurrentSpeechRequest("session-key", "session-key"))
        assertFalse(isCurrentSpeechRequest("old-session", "replacement-session"))
        assertFalse(isCurrentSpeechRequest("session-key", null))
        assertFalse(isCurrentSpeechRequest("", ""))
        assertFalse(isCurrentSpeechRequest(null, "session-key"))
    }
}
