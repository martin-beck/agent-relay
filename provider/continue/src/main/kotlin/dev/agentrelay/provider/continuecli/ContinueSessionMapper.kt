package dev.agentrelay.provider.continuecli

import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val CONTINUE_PROVIDER_ID = AgentProviderId("continue.cli")

internal object ContinueSessionMapper {
    fun fromListing(session: JsonObject): AgentSession {
        val id = requireNotNull(session.string("id")) { "Continue session is missing its id" }
        val title = session.string("title")?.takeIf(String::isNotBlank)
        val timestamp = session.string("timestamp")?.toEpochSeconds()
        return AgentSession(
            id = AgentSessionId(id),
            providerId = CONTINUE_PROVIDER_ID,
            title = title,
            preview = session.string("firstUserMessage")?.takeIf(String::isNotBlank)
                ?: title.orEmpty(),
            workingDirectory = session.string("workspaceDirectory"),
            model = null,
            createdAtEpochSeconds = timestamp,
            updatedAtEpochSeconds = timestamp,
            state = AgentSessionState.IDLE,
            canAcceptInput = true,
            metadata = buildMap {
                session.string("timestamp")?.let { put("continue.timestamp", it) }
                session.string("isRemote")?.let { put("continue.isRemote", it) }
            },
        )
    }

    fun fromState(sessionState: JsonObject, previous: AgentSession? = null): AgentSession {
        val session = requireNotNull(sessionState.objectValue("session")) {
            "Continue state is missing its session"
        }
        val id = requireNotNull(session.string("sessionId")) {
            "Continue state session is missing its id"
        }
        val history = session.arrayValue("history").orEmpty()
        val preview = history.firstNotNullOfOrNull { element ->
            element.objectOrNull()
                ?.objectValue("message")
                ?.takeIf { it.string("role") == "user" }
                ?.contentText()
                ?.takeIf(String::isNotBlank)
        } ?: previous?.preview.orEmpty()
        val waiting = sessionState.objectValue("pendingPermission") != null
        val processing = sessionState.boolean("isProcessing") == true ||
            (sessionState.long("messageQueueLength") ?: 0) > 0
        val state = when {
            waiting -> AgentSessionState.WAITING_FOR_APPROVAL
            processing -> AgentSessionState.RUNNING
            else -> AgentSessionState.IDLE
        }
        val model = history.asReversed().firstNotNullOfOrNull { element ->
            element.objectOrNull()
                ?.objectValue("message")
                ?.objectValue("usage")
                ?.string("model")
        } ?: previous?.model
        val usage = session.objectValue("usage")

        return AgentSession(
            id = AgentSessionId(id),
            providerId = CONTINUE_PROVIDER_ID,
            title = session.string("title")?.takeIf(String::isNotBlank) ?: previous?.title,
            preview = preview,
            workingDirectory = session.string("workspaceDirectory") ?: previous?.workingDirectory,
            model = model,
            createdAtEpochSeconds = previous?.createdAtEpochSeconds,
            updatedAtEpochSeconds = previous?.updatedAtEpochSeconds,
            state = state,
            canAcceptInput = state != AgentSessionState.RUNNING &&
                state != AgentSessionState.WAITING_FOR_APPROVAL,
            metadata = buildMap {
                previous?.metadata?.let(::putAll)
                usage?.forEach { (key, value) ->
                    (value as? JsonPrimitive)?.content?.let { put("usage.$key", it) }
                }
                put("continue.messageQueueLength", (sessionState.long("messageQueueLength") ?: 0).toString())
            },
        )
    }
}

internal fun JsonObject.contentText(): String? = when (val content = get("content")) {
    is JsonPrimitive -> content.content
    is JsonArray -> content.mapNotNull { part ->
        part.objectOrNull()?.string("text") ?: (part as? JsonPrimitive)?.content
    }.joinToString("")
    else -> null
}

private fun String.toEpochSeconds(): Long? = runCatching {
    Instant.parse(this).epochSecond
}.getOrNull()
