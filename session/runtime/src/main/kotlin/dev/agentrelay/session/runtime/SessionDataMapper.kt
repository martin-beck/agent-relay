package dev.agentrelay.session.runtime

import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentToolStatus
import dev.agentrelay.provider.api.AgentTranscriptEntry
import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.session.api.CachedTranscriptEntry
import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionObservation
import java.security.MessageDigest

internal data class SessionEventProjection(
    val activity: SessionActivity? = null,
    val transcriptEntry: CachedTranscriptEntry? = null,
    val state: AgentSessionState? = null,
    val preview: String? = null,
)

internal object SessionDataMapper {
    fun locator(
        endpoint: AgentEndpointKey,
        sessionId: dev.agentrelay.provider.api.AgentSessionId,
    ) = SessionLocator(
        connectionProviderId = endpoint.connection.providerId,
        connectionProfileId = endpoint.connection.profileId,
        agentProviderId = endpoint.agentProviderId,
        agentSessionId = sessionId,
    )

    fun observation(
        profile: ConnectionProfileSummary,
        descriptor: AgentProviderDescriptor,
        session: AgentSession,
    ) = SessionObservation(
        locator = locator(
            AgentEndpointKey(
                connection = SessionConnectionKey(profile.providerId, profile.id),
                agentProviderId = descriptor.id,
            ),
            session.id,
        ),
        connectionLabel = boundedRequired(profile.label, "Connection", MAX_LABEL_CHARS),
        connectionTarget = boundedRequired(profile.target, "Connection target", MAX_PATH_CHARS),
        projectPath = session.workingDirectory?.let { boundedNullable(it, MAX_PATH_CHARS) },
        agentProviderLabel = boundedRequired(descriptor.displayName, descriptor.id.value, MAX_LABEL_CHARS),
        title = session.title?.let { boundedNullable(it, MAX_TITLE_CHARS) },
        preview = session.preview.take(MAX_PREVIEW_CHARS),
        agentState = session.state,
        createdAtEpochMillis = epochSecondsToMillis(session.createdAtEpochSeconds),
        updatedAtEpochMillis = epochSecondsToMillis(session.updatedAtEpochSeconds),
        metadata = sessionMetadata(session),
    )

    fun placeholderObservation(
        profile: ConnectionProfileSummary,
        descriptor: AgentProviderDescriptor,
        locator: SessionLocator,
        preview: String,
        now: Long,
    ) = SessionObservation(
        locator = locator,
        connectionLabel = boundedRequired(profile.label, "Connection", MAX_LABEL_CHARS),
        connectionTarget = boundedRequired(profile.target, "Connection target", MAX_PATH_CHARS),
        projectPath = null,
        agentProviderLabel = boundedRequired(descriptor.displayName, descriptor.id.value, MAX_LABEL_CHARS),
        title = null,
        preview = preview.take(MAX_PREVIEW_CHARS),
        agentState = AgentSessionState.UNKNOWN,
        createdAtEpochMillis = null,
        updatedAtEpochMillis = now,
    )

    fun transcript(entry: AgentTranscriptEntry): CachedTranscriptEntry =
        CachedTranscriptEntry(
            id = boundedIdentifier("message", entry.id),
            turnId = entry.turnId?.value?.takeIf(String::isNotBlank)?.let {
                boundedIdentifier("turn", it)
            },
            role = entry.role,
            channel = entry.channel,
            text = entry.text.take(MAX_TRANSCRIPT_CHARS),
            createdAtEpochMillis = epochSecondsToMillis(entry.createdAtEpochSeconds),
            metadata = sanitizedMetadata(entry.metadata),
        )

    fun event(
        event: AgentEvent,
        locator: SessionLocator,
        now: Long,
    ): SessionEventProjection = when (event) {
        is AgentEvent.TextDelta -> SessionEventProjection()
        is AgentEvent.MessageCompleted -> {
            val text = event.text.take(MAX_TRANSCRIPT_CHARS)
            val messageId = boundedIdentifier(
                "message",
                event.itemId ?: event.turnId?.value
                    ?: (now.toString() + ":" + event.channel + ":" + digest(event.text)),
            )
            val summary = nonBlankSummary(event.text, "New agent output")
            SessionEventProjection(
                activity = SessionActivity(
                    id = ("output:" + messageId).boundedActivityId(),
                    locator = locator,
                    type = SessionActivityType.NEW_OUTPUT,
                    summary = summary,
                    eventAnchorId = messageId,
                    occurredAtEpochMillis = now,
                ),
                transcriptEntry = CachedTranscriptEntry(
                    id = messageId,
                    turnId = event.turnId?.value?.takeIf(String::isNotBlank)?.let {
                        boundedIdentifier("turn", it)
                    },
                    role = AgentTranscriptRole.AGENT,
                    channel = event.channel,
                    text = text,
                    createdAtEpochMillis = now,
                ),
                preview = summary,
            )
        }
        is AgentEvent.ToolChanged -> {
            if (event.status != AgentToolStatus.FAILED) {
                SessionEventProjection()
            } else {
                val anchor = boundedIdentifier("tool", event.itemId)
                SessionEventProjection(
                    activity = SessionActivity(
                        id = ("tool-failed:" + anchor).boundedActivityId(),
                        locator = locator,
                        type = SessionActivityType.FAILURE,
                        summary = nonBlankSummary(
                            event.summary,
                            event.toolName.take(256) + " failed",
                        ),
                        eventAnchorId = anchor,
                        occurredAtEpochMillis = now,
                    ),
                )
            }
        }
        is AgentEvent.ApprovalRequested -> {
            val anchor = boundedIdentifier("approval", event.approval.id.value)
            val question = event.approval.type == AgentApprovalType.USER_INPUT ||
                event.approval.questions.isNotEmpty()
            val summary = nonBlankSummary(
                event.approval.title,
                if (question) "Agent question requires an answer" else "Agent approval required",
            )
            SessionEventProjection(
                activity = SessionActivity(
                    id = ("approval:" + anchor).boundedActivityId(),
                    locator = locator,
                    type = if (question) SessionActivityType.QUESTION else SessionActivityType.APPROVAL_REQUIRED,
                    summary = summary,
                    eventAnchorId = anchor,
                    occurredAtEpochMillis = now,
                ),
                state = AgentSessionState.WAITING_FOR_APPROVAL,
                preview = summary,
            )
        }
        is AgentEvent.FileChanged -> SessionEventProjection()
        is AgentEvent.TurnCompleted -> {
            val anchor = boundedIdentifier(
                "turn",
                event.turnId?.value ?: (now.toString() + ":" + event.successful),
            )
            val summary = if (event.successful) {
                "Agent turn completed"
            } else {
                nonBlankSummary(event.errorMessage, "Agent turn failed")
            }
            SessionEventProjection(
                activity = SessionActivity(
                    id = ("turn-completed:" + anchor).boundedActivityId(),
                    locator = locator,
                    type = if (event.successful) {
                        SessionActivityType.TURN_COMPLETED
                    } else {
                        SessionActivityType.FAILURE
                    },
                    summary = summary,
                    eventAnchorId = anchor,
                    occurredAtEpochMillis = now,
                ),
                state = if (event.successful) AgentSessionState.IDLE else AgentSessionState.FAILED,
                preview = summary,
            )
        }
        is AgentEvent.SessionStateChanged -> SessionEventProjection(state = event.state)
        is AgentEvent.Error -> {
            val anchor = boundedIdentifier("error", now.toString() + ":" + digest(event.message))
            val summary = nonBlankSummary(event.message, "Agent provider failed")
            SessionEventProjection(
                activity = SessionActivity(
                    id = ("error:" + anchor).boundedActivityId(),
                    locator = locator,
                    type = SessionActivityType.FAILURE,
                    summary = summary,
                    eventAnchorId = anchor,
                    occurredAtEpochMillis = now,
                ),
                state = AgentSessionState.FAILED,
                preview = summary,
            )
        }
    }

    private fun sessionMetadata(session: AgentSession): Map<String, String> {
        val sanitized = sanitizedMetadata(session.metadata).toMutableMap()
        session.model?.takeIf(String::isNotBlank)?.let {
            if ("model" !in sanitized && sanitized.size < MAX_METADATA_ENTRIES) {
                sanitized["model"] = it.take(MAX_METADATA_VALUE_CHARS)
            }
        }
        sanitized["can_accept_input"] = session.canAcceptInput.toString()
        return sanitized.entries.take(MAX_METADATA_ENTRIES).associate { it.toPair() }
    }

    private fun sanitizedMetadata(metadata: Map<String, String>): Map<String, String> =
        metadata.entries
            .asSequence()
            .filter { it.key.isNotBlank() }
            .map {
                it.key.take(MAX_METADATA_KEY_CHARS) to it.value.take(MAX_METADATA_VALUE_CHARS)
            }
            .distinctBy { it.first }
            .take(MAX_METADATA_ENTRIES)
            .toMap()

    private fun epochSecondsToMillis(seconds: Long?): Long? {
        if (seconds == null || seconds < 0L) {
            return null
        }
        return if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
    }

    private fun boundedRequired(value: String, fallback: String, maximum: Int): String =
        value.trim().takeIf(String::isNotEmpty)?.take(maximum) ?: fallback.take(maximum)

    private fun boundedNullable(value: String, maximum: Int): String? =
        value.trim().takeIf(String::isNotEmpty)?.take(maximum)

    private fun nonBlankSummary(value: String?, fallback: String): String =
        value?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_ACTIVITY_CHARS)
            ?: fallback.take(MAX_ACTIVITY_CHARS)

    private fun boundedIdentifier(prefix: String, value: String): String {
        val candidate = value.trim()
        return if (candidate.isNotEmpty() && candidate.length <= MAX_ID_CHARS) {
            candidate
        } else {
            prefix + ":" + digest(value)
        }
    }

    private fun String.boundedActivityId(): String =
        if (length <= MAX_ID_CHARS) this else "activity:" + digest(this)

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private const val MAX_ID_CHARS = 512
    private const val MAX_LABEL_CHARS = 256
    private const val MAX_TITLE_CHARS = 1_024
    private const val MAX_PATH_CHARS = 4_096
    private const val MAX_PREVIEW_CHARS = 16_384
    private const val MAX_ACTIVITY_CHARS = 16_384
    private const val MAX_TRANSCRIPT_CHARS = 1024 * 1024
    private const val MAX_METADATA_ENTRIES = 64
    private const val MAX_METADATA_KEY_CHARS = 256
    private const val MAX_METADATA_VALUE_CHARS = 4_096
}
