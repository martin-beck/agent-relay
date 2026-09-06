package dev.agentrelay.provider.api

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class AgentProviderRegistryTest {
    @Test
    fun descriptorsAreSortedByDisplayName() {
        val registry = AgentProviderRegistry(
            listOf(
                factory("test.zulu", "Zulu"),
                factory("test.alpha", "Alpha"),
            ),
        )

        assertEquals(listOf("Alpha", "Zulu"), registry.descriptors().map { it.displayName })
        assertEquals("test.alpha", registry.factory(AgentProviderId("test.alpha")).descriptor.id.value)
    }

    @Test
    fun duplicateProviderIdsAreRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            AgentProviderRegistry(
                listOf(
                    factory("test.same", "First"),
                    factory("test.same", "Second"),
                ),
            )
        }

        assertEquals(true, error.message?.contains("Duplicate provider ids"))
    }

    @Test
    fun incompatibleApiVersionsAreRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            AgentProviderRegistry(
                listOf(
                    factory("test.future", "Future", apiVersion = AGENT_PROVIDER_API_VERSION + 1),
                ),
            )
        }

        assertEquals(true, error.message?.contains("Provider API mismatch"))
    }

    @Test
    fun providerIdentifiersRejectUnstableValues() {
        listOf("", "UPPERCASE", "contains spaces", "a").forEach { value ->
            assertFailsWith<IllegalArgumentException> { AgentProviderId(value) }
        }
    }

    @Test
    fun providerDescriptorsRequireStableMetadata() {
        assertFailsWith<IllegalArgumentException> {
            AgentProviderDescriptor(
                id = AgentProviderId("test.valid"),
                displayName = " ",
                providerVersion = "1.0.0",
                capabilities = emptySet(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentProviderDescriptor(
                id = AgentProviderId("test.valid"),
                displayName = "Valid",
                providerVersion = "",
                capabilities = emptySet(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentProviderDescriptor(
                id = AgentProviderId("test.valid"),
                displayName = "Valid",
                providerVersion = "1.0.0",
                apiVersion = 0,
                capabilities = emptySet(),
            )
        }
    }

    private fun factory(
        id: String,
        name: String,
        apiVersion: Int = AGENT_PROVIDER_API_VERSION,
    ): AgentProviderFactory = object : AgentProviderFactory {
        override val descriptor = AgentProviderDescriptor(
            id = AgentProviderId(id),
            displayName = name,
            providerVersion = "1.0.0",
            apiVersion = apiVersion,
            capabilities = emptySet(),
        )

        override suspend fun probe(runtime: RemoteAgentRuntime): ProviderReadiness = ProviderReadiness.Ready("1.0.0")

        override suspend fun connect(runtime: RemoteAgentRuntime): AgentProviderConnection {
            error("Not used by registry tests")
        }
    }
}
