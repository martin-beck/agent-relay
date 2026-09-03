package dev.agentrelay.session.runtime

import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.provider.api.AgentApproval
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
import dev.agentrelay.session.api.SessionActivitySummary
import dev.agentrelay.session.api.SessionActivitySummaryKind
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionActionRequest
import dev.agentrelay.session.api.SessionActionRisk
import dev.agentrelay.session.api.SessionArtifact
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionPresentationText
import dev.agentrelay.session.api.SessionPresentationTextKind
import dev.agentrelay.session.api.SessionObservation
import dev.agentrelay.session.api.SessionQuestion
import dev.agentrelay.session.api.SessionQuestionOption
import java.security.MessageDigest
import java.util.Locale

internal data class SessionEventProjection(
    val activity: SessionActivity? = null,
    val transcriptEntry: CachedTranscriptEntry? = null,
    val actionRequest: SessionActionRequest? = null,
    val artifact: SessionArtifact? = null,
    val state: AgentSessionState? = null,
    val preview: String? = null,
) {
    val isEmpty: Boolean
        get() = listOf(activity, transcriptEntry, actionRequest, state, preview)
            .all { it == null } && artifact == null
}

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
        workspaceRoot: String? = null,
    ): SessionEventProjection = when (event) {
        is AgentEvent.TextDelta -> SessionEventProjection()
        is AgentEvent.MessageCompleted -> {
            val text = event.text.take(MAX_TRANSCRIPT_CHARS)
            val messageId = boundedIdentifier(
                "message",
                event.itemId ?: event.turnId?.value
                    ?: (now.toString() + ":" + event.channel + ":" + digest(event.text)),
            )
            val providerSummary = boundedProviderSummary(event.text)
            val summary = generatedActivitySummary(
                event.text,
                SessionActivitySummaryKind.NEW_AGENT_OUTPUT,
            )
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
                preview = providerSummary,
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
                        summary = event.activitySummary(),
                        eventAnchorId = anchor,
                        occurredAtEpochMillis = now,
                    ),
                )
            }
        }
        is AgentEvent.ApprovalRequested -> approvalEvent(event, locator, now)
        is AgentEvent.FileChanged -> SessionEventProjection(
            artifact = SessionArtifactMapper.map(
                file = event.file,
                locator = locator,
                workspaceRoot = workspaceRoot,
                now = now,
            ),
        )
        is AgentEvent.TurnCompleted -> {
            val anchor = boundedIdentifier(
                "turn",
                event.turnId?.value ?: (now.toString() + ":" + event.successful),
            )
            val providerSummary = if (event.successful) {
                null
            } else {
                boundedProviderSummary(event.errorMessage)
            }
            val summary = event.activitySummary()
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
                preview = providerSummary,
            )
        }
        is AgentEvent.SessionStateChanged -> SessionEventProjection(state = event.state)
        is AgentEvent.Error -> {
            val anchor = boundedIdentifier("error", now.toString() + ":" + digest(event.message))
            val providerSummary = boundedProviderSummary(event.message)
            val summary = generatedActivitySummary(
                event.message,
                SessionActivitySummaryKind.AGENT_PROVIDER_FAILED,
            )
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
                preview = providerSummary,
            )
        }
    }

    private fun approvalEvent(
        event: AgentEvent.ApprovalRequested,
        locator: SessionLocator,
        now: Long,
    ): SessionEventProjection {
        val request = actionRequest(event.approval, locator, now)
        val question = event.approval.type == AgentApprovalType.USER_INPUT ||
            event.approval.questions.isNotEmpty()
        val providerSummary = boundedProviderSummary(event.approval.title)
        val summary = generatedActivitySummary(
            event.approval.title,
            if (question) {
                SessionActivitySummaryKind.AGENT_QUESTION_REQUIRES_ANSWER
            } else {
                SessionActivitySummaryKind.AGENT_APPROVAL_REQUIRED
            },
        )
        return SessionEventProjection(
            activity = SessionActivity(
                id = ("approval:" + request.id).boundedActivityId(),
                locator = locator,
                type = if (question) {
                    SessionActivityType.QUESTION
                } else {
                    SessionActivityType.APPROVAL_REQUIRED
                },
                summary = summary,
                eventAnchorId = request.id,
                actionRequestId = request.id,
                occurredAtEpochMillis = now,
            ),
            actionRequest = request,
            state = AgentSessionState.WAITING_FOR_APPROVAL,
            preview = providerSummary,
        )
    }

    private fun actionRequest(
        approval: AgentApproval,
        locator: SessionLocator,
        now: Long,
    ): SessionActionRequest {
        val providerApprovalId = providerRequestId(approval.id.value, "Approval id")
        val requestId = "action:" + digest(locator.stableKey + "\u0000" + providerApprovalId)
        require(approval.questions.size <= MAX_QUESTIONS) { "Approval contains too many questions" }
        val providerQuestionIds = approval.questions.map {
            providerRequestId(it.id, "Question id")
        }
        require(providerQuestionIds.distinct().size == providerQuestionIds.size) {
            "Approval contains duplicate question ids"
        }
        val questions = approval.questions.mapIndexed { index, question ->
            val providerQuestionId = providerQuestionIds[index]
            val header = question.header?.let { boundedNullable(it, MAX_LABEL_CHARS) }
            require(question.options.size <= MAX_QUESTION_OPTIONS) {
                "Approval question contains too many options"
            }
            SessionQuestion(
                id = "question:" + digest(requestId + "\u0000" + providerQuestionId),
                providerQuestionId = providerQuestionId,
                header = header,
                prompt = providerPresentationText(
                    value = question.prompt,
                    providerFallback = header,
                    generatedKind = SessionPresentationTextKind.AGENT_QUESTION,
                    maximum = MAX_DESCRIPTION_CHARS,
                ),
                options = question.options.map { option ->
                    SessionQuestionOption(
                        label = providerOptionLabel(option.label),
                        description = option.description?.let {
                            boundedNullable(it, MAX_DESCRIPTION_CHARS)
                        },
                    )
                },
                allowsOther = question.allowsOther,
                allowsMultiple = question.allowsMultiple,
            )
        }
        require(approval.availableDecisions.isNotEmpty()) { "Approval exposes no decisions" }
        return SessionActionRequest(
            id = requestId,
            providerApprovalId = providerApprovalId,
            locator = locator,
            turnId = approval.turnId?.value?.takeIf(String::isNotBlank)?.let {
                boundedIdentifier("turn", it)
            },
            type = approval.type,
            title = providerPresentationText(
                value = approval.title,
                providerFallback = null,
                generatedKind = SessionPresentationTextKind.ACTION_REVIEW_REQUIRED,
                maximum = MAX_TITLE_CHARS,
            ),
            description = approval.description?.let { boundedNullable(it, MAX_DESCRIPTION_CHARS) },
            command = approval.command?.let { boundedNullable(it, MAX_COMMAND_CHARS) },
            workingDirectory = approval.workingDirectory?.let { boundedNullable(it, MAX_PATH_CHARS) },
            questions = questions,
            availableDecisions = approval.availableDecisions,
            riskReasons = actionRisks(approval),
            receivedAtEpochMillis = now,
        )
    }

    private fun providerRequestId(value: String, label: String): String {
        require(value.isNotBlank()) { "$label must not be blank" }
        require(value.length <= MAX_PROVIDER_REQUEST_ID_CHARS) { "$label is too large" }
        return value
    }

    private fun providerOptionLabel(value: String): String {
        require(value.isNotBlank()) { "Question option must not be blank" }
        require(value.length <= MAX_QUESTION_OPTION_CHARS) { "Question option is too large" }
        return value
    }

    private fun actionRisks(approval: AgentApproval): Set<SessionActionRisk> {
        val text = listOfNotNull(
            approval.title,
            approval.description,
            approval.command,
            approval.workingDirectory,
        ).joinToString("\n").lowercase(Locale.ROOT)
        return buildSet {
            if (approval.type == AgentApprovalType.EXTERNAL_TOOL) {
                add(SessionActionRisk.EXTERNAL_TOOL)
            }
            if (DESTRUCTIVE_TERMS.any(text::contains)) {
                add(SessionActionRisk.DESTRUCTIVE_COMMAND)
            }
            if (BROAD_FILESYSTEM_TERMS.any(text::contains) || isBroadPath(approval.workingDirectory)) {
                add(SessionActionRisk.BROAD_FILESYSTEM_ACCESS)
            }
            if (CREDENTIAL_TERMS.any(text::contains)) {
                add(SessionActionRisk.CREDENTIAL_ACCESS)
            }
            if (NETWORK_TERMS.any(text::contains)) {
                add(SessionActionRisk.NETWORK_EXPANSION)
            }
        }
    }

    private fun isBroadPath(path: String?): Boolean =
        path?.trim()?.lowercase(Locale.ROOT)?.trimEnd('/', '\\') in BROAD_PATHS

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

    private const val MAX_ID_CHARS = 512
    private const val MAX_TITLE_CHARS = 1_024
    private const val MAX_PATH_CHARS = 4_096
    private const val MAX_PREVIEW_CHARS = 16_384
    private const val MAX_TRANSCRIPT_CHARS = 1024 * 1024
    private const val MAX_DESCRIPTION_CHARS = 16_384
    private const val MAX_COMMAND_CHARS = 64 * 1024
    private const val MAX_PROVIDER_REQUEST_ID_CHARS = 4_096
    private const val MAX_QUESTIONS = 32
    private const val MAX_QUESTION_OPTIONS = 64
    private const val MAX_QUESTION_OPTION_CHARS = 4_096
    private const val MAX_METADATA_ENTRIES = 64
    private const val MAX_METADATA_KEY_CHARS = 256
    private const val MAX_METADATA_VALUE_CHARS = 4_096

    private val DESTRUCTIVE_TERMS = listOf(
        "rm -", "remove-item", "del /", "format ", "mkfs", "git clean",
        "reset --hard", "drop database", "truncate table", "kubectl delete",
        "terraform destroy", "shutdown", "reboot",
    )
    private val BROAD_FILESYSTEM_TERMS = listOf(
        "entire filesystem",
        "all files",
        "recursive /",
        " -rf /",
        "chmod -r /",
        "chown -r /",
    )
    private val CREDENTIAL_TERMS = listOf(
        "credential", "password", "access token", "api token", "secret", "private key",
        "authorized_keys", ".ssh", ".env", "keychain", "keystore", "vault",
    )
    private val NETWORK_TERMS = listOf(
        "curl ", "wget ", "ssh ", "scp ", "sftp ", "netcat", "socat", "iptables",
        "firewall", "port forward", "proxy", "vpn", "public listener",
    )
    private val BROAD_PATHS = setOf("", "/", "/home", "/root", "~", "\$home", "c:", "c:\\", "c:\\users")
}

private fun providerPresentationText(
    value: String,
    providerFallback: String?,
    generatedKind: SessionPresentationTextKind,
    maximum: Int,
): SessionPresentationText = (value.trim().takeIf(String::isNotEmpty) ?: providerFallback)
    ?.take(maximum)
    ?.let(SessionPresentationText::Verbatim)
    ?: SessionPresentationText.Generated(generatedKind)

private fun generatedActivitySummary(
    providerSummary: String?,
    fallbackKind: SessionActivitySummaryKind,
): SessionActivitySummary = boundedProviderSummary(providerSummary)
    ?.let(SessionActivitySummary::Verbatim)
    ?: SessionActivitySummary.Generated(fallbackKind)

private fun AgentEvent.ToolChanged.activitySummary(): SessionActivitySummary =
    boundedProviderSummary(summary)?.let(SessionActivitySummary::Verbatim)
        ?: boundedProviderLabel(toolName)?.let { boundedToolName ->
            SessionActivitySummary.Generated(
                SessionActivitySummaryKind.NAMED_TOOL_FAILED,
                boundedToolName,
            )
        }
        ?: SessionActivitySummary.Generated(SessionActivitySummaryKind.TOOL_FAILED)

private fun AgentEvent.TurnCompleted.activitySummary(): SessionActivitySummary =
    if (successful) {
        SessionActivitySummary.Generated(SessionActivitySummaryKind.AGENT_TURN_COMPLETED)
    } else {
        generatedActivitySummary(errorMessage, SessionActivitySummaryKind.AGENT_TURN_FAILED)
    }

private fun boundedProviderSummary(value: String?): String? =
    value?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_ACTIVITY_CHARS)

private fun boundedProviderLabel(value: String?): String? =
    value?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_LABEL_CHARS)

private const val MAX_LABEL_CHARS = 256
private const val MAX_ACTIVITY_CHARS = 16_384

private fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
