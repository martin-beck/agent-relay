package dev.agentrelay.session.runtime

import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteFileSnapshot
import dev.agentrelay.session.api.SessionArtifact
import kotlinx.coroutines.flow.Flow

data class PreparedArtifactDownload(
    val artifact: SessionArtifact,
    val sourceSnapshot: RemoteFileSnapshot,
    val chunks: Flow<ByteArray>,
)

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
    val fileAccessAvailable: Boolean = false,
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

/**
 * Describes a coordinator failure without owning presentation text. Labels are bounded opaque
 * arguments; the UI owns the localized sentence structure for each [kind].
 */
data class SessionCoordinatorIssue(
    val id: String,
    val kind: SessionCoordinatorIssueKind,
    val connection: SessionConnectionKey?,
    val agentProviderId: AgentProviderId?,
    val connectionProviderLabel: String? = null,
    val connectionLabel: String? = null,
    val agentProviderLabel: String? = null,
    val recoverable: Boolean,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(id.isNotBlank() && id.length <= 512)
        when (kind) {
            SessionCoordinatorIssueKind.PROFILE_DISCOVERY -> {
                require(connectionProviderLabel.isValidIssueLabel())
                require(connectionLabel == null && agentProviderLabel == null)
            }
            SessionCoordinatorIssueKind.CONNECTION_SETUP -> {
                require(connectionLabel.isValidIssueLabel())
                require(connectionProviderLabel == null && agentProviderLabel == null)
            }
            SessionCoordinatorIssueKind.PROVIDER_SYNCHRONIZATION -> {
                require(connectionLabel.isValidIssueLabel())
                require(agentProviderLabel.isValidIssueLabel())
                require(connectionProviderLabel == null)
            }
            SessionCoordinatorIssueKind.SESSION_PERSISTENCE -> {
                require(agentProviderLabel.isValidIssueLabel())
                require(connectionProviderLabel == null && connectionLabel == null)
            }
        }
        require(occurredAtEpochMillis >= 0L)
    }
}

private const val MAX_ISSUE_LABEL_LENGTH = 256

private fun String?.isValidIssueLabel(): Boolean =
    this != null && isNotBlank() && length <= MAX_ISSUE_LABEL_LENGTH

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
