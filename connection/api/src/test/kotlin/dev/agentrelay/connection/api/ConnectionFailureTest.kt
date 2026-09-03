package dev.agentrelay.connection.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ConnectionFailureTest {
    @Test
    fun providerGuidanceIsExplicitlyVerbatim() {
        val failure = ConnectionFailure(
            category = ConnectionFailureCategory.NETWORK,
            code = "NETWORK_UNREACHABLE",
            actionableMessage = "Check the provider network and retry.",
            recoverable = true,
        )

        assertEquals("Check the provider network and retry.", failure.actionableMessage)
        assertIs<ConnectionFailureMessage.Verbatim>(failure.message)
    }

    @Test
    fun generatedFailureUsesItsStableCodeOutsidePresentation() {
        val failure = ConnectionFailure(
            category = ConnectionFailureCategory.CONFIGURATION,
            code = "CONNECTION_SETUP_FAILED",
            message = ConnectionFailureMessage.Generated(
                ConnectionFailureMessageKind.PROFILE_PREPARATION_FAILED,
            ),
            recoverable = true,
        )

        assertEquals("CONNECTION_SETUP_FAILED", failure.actionableMessage)
    }

    @Test
    fun blankProviderGuidanceIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionFailure(
                category = ConnectionFailureCategory.UNKNOWN,
                code = "UNKNOWN_FAILURE",
                actionableMessage = " ",
                recoverable = false,
            )
        }
    }
}
