package dev.agentrelay.session.android

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.session.api.CachedTranscriptEntry
import dev.agentrelay.session.api.SessionActionRequest
import dev.agentrelay.session.api.SessionActionRisk
import dev.agentrelay.session.api.SessionActionState
import dev.agentrelay.session.api.SessionQuestion
import dev.agentrelay.session.api.SessionQuestionOption

internal fun SessionQuestionOption.toDocument() = SessionQuestionOptionDocument(
    label = label,
    description = description,
)

private fun SessionQuestionOptionDocument.toDomain() = SessionQuestionOption(
    label = label,
    description = description,
)

internal fun SessionQuestion.toDocument() = SessionQuestionDocument(
    id = id,
    providerQuestionId = providerQuestionId,
    header = header,
    prompt = prompt,
    options = options.map(SessionQuestionOption::toDocument),
    allowsOther = allowsOther,
    allowsMultiple = allowsMultiple,
)

private fun SessionQuestionDocument.toDomain() = SessionQuestion(
    id = id,
    providerQuestionId = providerQuestionId,
    header = header,
    prompt = prompt,
    options = options.map(SessionQuestionOptionDocument::toDomain),
    allowsOther = allowsOther,
    allowsMultiple = allowsMultiple,
)

internal fun SessionActionRequest.toDocument() = SessionActionRequestDocument(
    id = id,
    providerApprovalId = providerApprovalId,
    locator = locator.toDocument(),
    turnId = turnId,
    type = type.name,
    title = title,
    description = description,
    command = command,
    workingDirectory = workingDirectory,
    questions = questions.map(SessionQuestion::toDocument),
    availableDecisions = availableDecisions
        .sortedBy(AgentApprovalDecision::ordinal)
        .map(AgentApprovalDecision::name),
    riskReasons = riskReasons
        .sortedBy(SessionActionRisk::ordinal)
        .map(SessionActionRisk::name),
    receivedAtEpochMillis = receivedAtEpochMillis,
    state = state.name,
    decision = decision?.name,
    answeredQuestionIds = answeredQuestionIds.sorted(),
    additionalConfirmationGiven = additionalConfirmationGiven,
    decisionAtEpochMillis = decisionAtEpochMillis,
)

internal fun SessionActionRequestDocument.toDomain() = SessionActionRequest(
    id = id,
    providerApprovalId = providerApprovalId,
    locator = locator.toDomain(),
    turnId = turnId,
    type = persistedEnumValue(type),
    title = title,
    description = description,
    command = command,
    workingDirectory = workingDirectory,
    questions = questions.map(SessionQuestionDocument::toDomain),
    availableDecisions = availableDecisions
        .requireUniqueValues("action decision")
        .mapTo(linkedSetOf()) { persistedEnumValue<AgentApprovalDecision>(it) },
    riskReasons = riskReasons
        .requireUniqueValues("action risk")
        .mapTo(linkedSetOf()) { persistedEnumValue<SessionActionRisk>(it) },
    receivedAtEpochMillis = receivedAtEpochMillis,
    state = persistedEnumValue<SessionActionState>(state),
    decision = decision?.let { persistedEnumValue<AgentApprovalDecision>(it) },
    answeredQuestionIds = answeredQuestionIds
        .requireUniqueValues("answered question id")
        .toSet(),
    additionalConfirmationGiven = additionalConfirmationGiven,
    decisionAtEpochMillis = decisionAtEpochMillis,
)

internal fun CachedTranscriptEntry.toDocument() = CachedTranscriptEntryDocument(
    id = id,
    turnId = turnId,
    role = role.name,
    channel = channel?.name,
    text = text,
    createdAtEpochMillis = createdAtEpochMillis,
    metadata = metadata,
)

internal fun CachedTranscriptEntryDocument.toDomain() = CachedTranscriptEntry(
    id = id,
    turnId = turnId,
    role = persistedEnumValue(role),
    channel = channel?.let { persistedEnumValue<AgentMessageChannel>(it) },
    text = text,
    createdAtEpochMillis = createdAtEpochMillis,
    metadata = metadata,
)

private fun <T> List<T>.requireUniqueValues(type: String): List<T> {
    check(distinct().size == size) { "Duplicate $type records" }
    return this
}

private inline fun <reified T : Enum<T>> persistedEnumValue(name: String): T =
    enumValues<T>().firstOrNull { it.name == name }
        ?: throw IllegalArgumentException("Unknown persisted enum value")
