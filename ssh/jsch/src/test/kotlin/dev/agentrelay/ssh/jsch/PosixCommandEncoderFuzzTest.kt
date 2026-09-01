package dev.agentrelay.ssh.jsch

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest

class PosixCommandEncoderFuzzTest {
    @FuzzTest(maxDuration = "30s")
    fun quoteRoundTrips(data: FuzzedDataProvider) {
        val value = data.consumeRemainingAsString()
        assertPosixQuoteRoundTrips(value)
    }
}
