package dev.agentrelay.session.runtime

import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.ProviderReadiness

data class SessionConnectionKey(
    val providerId: ConnectionProviderId,
    val profileId: ConnectionProfileId,
)

data class AgentEndpointKey(
    val connection: SessionConnectionKey,
    val agentProviderId: AgentProviderId,
)

enum class AgentEndpointPhase {
    OFFLINE,
    PROBING,
    CONNECTING,
    READY,
    UNAVAILABLE,
    FAILED,
}

data class AgentEndpointStatus(
    val key: AgentEndpointKey,
    val descriptor: AgentProviderDescriptor,
    val phase: AgentEndpointPhase,
    val readiness: ProviderReadiness? = null,
    val sessionCount: Int = 0,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(sessionCount >= 0) { "Endpoint session count must not be negative" }
        require(updatedAtEpochMillis >= 0L)
    }
}

enum class SessionCoordinatorIssueKind {
    PROFILE_DISCOVERY,
    CONNECTION_SETUP,
    PROVIDER_SYNCHRONIZATION,
    SESSION_PERSISTENCE,
}

data class SessionCoordinatorIssue(
    val id: String,
    val kind: SessionCoordinatorIssueKind,
    val connection: SessionConnectionKey?,
    val agentProviderId: AgentProviderId?,
    val actionableMessage: String,
    val recoverable: Boolean,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank() && id.length <= 512)
        require(actionableMessage.isNotBlank() && actionableMessage.length <= 4_096)
        require(occurredAtEpochMillis >= 0L)
    }
}

data class SessionCoordinatorSnapshot(
    val profiles: List<ConnectionProfileSummary> = emptyList(),
    val connectionStates: Map<SessionConnectionKey, ConnectionState> = emptyMap(),
    val agentEndpoints: Map<AgentEndpointKey, AgentEndpointStatus> = emptyMap(),
    val issues: Map<String, SessionCoordinatorIssue> = emptyMap(),
    val isRefreshingProfiles: Boolean = false,
) {
    init {
        require(profiles.distinctBy { SessionConnectionKey(it.providerId, it.id) }.size == profiles.size) {
            "Coordinator snapshot contains duplicate connection profiles"
        }
        require(issues.keys.all { it.isNotBlank() }) {
            "Coordinator issue keys must not be blank"
        }
    }

    fun profile(key: SessionConnectionKey): ConnectionProfileSummary? =
        profiles.firstOrNull { it.providerId == key.providerId && it.id == key.profileId }
}

fun interface SessionCoordinatorClock {
    fun epochMillis(): Long
}
