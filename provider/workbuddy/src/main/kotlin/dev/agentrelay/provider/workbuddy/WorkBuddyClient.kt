/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.workbuddy

import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal sealed interface WorkBuddyOnlineResult {
    data class Available(val online: Boolean) : WorkBuddyOnlineResult
    data object AuthenticationRequired : WorkBuddyOnlineResult
    data object AuthorizationDenied : WorkBuddyOnlineResult
    data object Unavailable : WorkBuddyOnlineResult
}

internal data class WorkBuddyMessage(
    val id: String,
    val role: AgentTranscriptRole,
    val text: String,
    val createdAtEpochSeconds: Long,
)

internal sealed interface WorkBuddySendResult {
    data class Accepted(val messageId: String) : WorkBuddySendResult
    data object AuthenticationRequired : WorkBuddySendResult
    data object AuthorizationDenied : WorkBuddySendResult
    data object RateLimited : WorkBuddySendResult
    data object UnknownOutcome : WorkBuddySendResult
    data object Failed : WorkBuddySendResult
}

internal interface WorkBuddyClient {
    suspend fun onlineStatus(): WorkBuddyOnlineResult

    suspend fun history(limit: Int = MAX_HISTORY_PAGE_SIZE, offset: Int = 0): List<WorkBuddyMessage>

    suspend fun sendText(text: String): WorkBuddySendResult
}

internal class RemoteWorkBuddyClient(private val runtime: RemoteAgentRuntime) : WorkBuddyClient {
    override suspend fun onlineStatus(): WorkBuddyOnlineResult {
        val response = request(OPERATION_STATUS)
        return when (response.kind) {
            ResponseKind.OK -> runCatching {
                WorkBuddyOnlineResult.Available(response.body.requireData().boolean("online"))
            }.getOrDefault(WorkBuddyOnlineResult.Unavailable)
            ResponseKind.AUTHENTICATION -> WorkBuddyOnlineResult.AuthenticationRequired
            ResponseKind.AUTHORIZATION -> WorkBuddyOnlineResult.AuthorizationDenied
            else -> WorkBuddyOnlineResult.Unavailable
        }
    }

    override suspend fun history(limit: Int, offset: Int): List<WorkBuddyMessage> {
        require(limit in 1..MAX_HISTORY_PAGE_SIZE) { "History limit must be between 1 and 100" }
        require(offset in 0..MAX_HISTORY_OFFSET) { "History offset is out of range" }
        val response = request(
            OPERATION_HISTORY,
            buildJsonObject {
                put("limit", limit)
                put("offset", offset)
            },
        )
        when (response.kind) {
            ResponseKind.AUTHENTICATION -> error("WorkBuddy authentication is required")
            ResponseKind.AUTHORIZATION -> error("WorkBuddy Local Assistant permission is unavailable")
            ResponseKind.OK -> Unit
            else -> error("WorkBuddy history is unavailable")
        }
        val messages = response.body.requireData().array("messages")
        require(messages.size <= limit) { "WorkBuddy history exceeded the requested page size" }
        val ids = mutableSetOf<String>()
        return messages.map { element ->
            val message = element.requireObject("message")
            val id = message.string("message_id").requireOpaqueId("message id")
            require(ids.add(id)) { "WorkBuddy history contains a duplicate message id" }
            require(message.string("msg_type") == "text") { "Unsupported WorkBuddy message type" }
            require(message.array("attachments").isEmpty()) { "WorkBuddy attachments are unsupported" }
            require(message["metadata"] is JsonObject) { "Invalid WorkBuddy metadata" }
            val role = when (message.string("role")) {
                "user" -> AgentTranscriptRole.USER
                "assistant" -> AgentTranscriptRole.AGENT
                else -> error("Unsupported WorkBuddy message role")
            }
            val content = message.array("content")
            require(content.size <= MAX_CONTENT_PARTS) { "WorkBuddy message has too many content parts" }
            val text = content.joinToString("\n") { part ->
                part.requireString("message content").also {
                    require(it.length <= MAX_MESSAGE_LENGTH) { "WorkBuddy message content is too long" }
                }
            }
            require(text.length <= MAX_MESSAGE_LENGTH) { "WorkBuddy message content is too long" }
            val createdAt = runCatching { Instant.parse(message.string("created_at")).epochSecond }
                .getOrElse { error("Invalid WorkBuddy message timestamp") }
            WorkBuddyMessage(id, role, text, createdAt)
        }
    }

    override suspend fun sendText(text: String): WorkBuddySendResult {
        require(text.isNotBlank()) { "WorkBuddy text must not be blank" }
        require(text.length <= MAX_MESSAGE_LENGTH) { "WorkBuddy text is too long" }
        val input = buildJsonObject {
            put("content", text)
        }
        val response = request(OPERATION_SEND, input)
        return when (response.kind) {
            ResponseKind.OK -> runCatching {
                WorkBuddySendResult.Accepted(
                    response.body.requireData().string("message_id").requireOpaqueId("message id"),
                )
            }.getOrDefault(WorkBuddySendResult.UnknownOutcome)
            ResponseKind.AUTHENTICATION -> WorkBuddySendResult.AuthenticationRequired
            ResponseKind.AUTHORIZATION -> WorkBuddySendResult.AuthorizationDenied
            ResponseKind.RATE_LIMIT -> WorkBuddySendResult.RateLimited
            ResponseKind.UNKNOWN_OUTCOME -> WorkBuddySendResult.UnknownOutcome
            ResponseKind.FAILURE -> WorkBuddySendResult.Failed
        }
    }

    private suspend fun request(
        operation: String,
        input: JsonObject = JsonObject(emptyMap()),
    ): WorkBuddyResponse {
        val first = executeOnce(operation, input)
        val response = if (operation != OPERATION_SEND && first.kind == ResponseKind.FAILURE) {
            executeOnce(operation, input)
        } else {
            first
        }
        if (response.kind != ResponseKind.OK) return response
        return runCatching {
            require(response.rawBody.length <= MAX_RESPONSE_LENGTH) { "WorkBuddy response is too large" }
            val root = JSON.parseToJsonElement(response.rawBody).requireObject("response")
            require(root.number("code") == 0L) { "WorkBuddy returned an unsuccessful response" }
            root.string("request_id").requireOpaqueId("request id")
            WorkBuddyResponse(ResponseKind.OK, root = root)
        }.getOrElse {
            WorkBuddyResponse(
                if (operation == OPERATION_SEND) ResponseKind.UNKNOWN_OUTCOME else ResponseKind.FAILURE,
            )
        }
    }

    private suspend fun executeOnce(
        operation: String,
        input: JsonObject,
    ): WorkBuddyResponse {
        var submissionAttempted = false
        var process: RemoteDuplexProcess? = null
        val result = try {
            process = runtime.openProcess(
                RemoteCommand(
                    program = "python3",
                    arguments = listOf("-c", HTTP_HELPER, operation),
                ),
            )
            withTimeout(REQUEST_DEADLINE) {
                if (operation == OPERATION_SEND) submissionAttempted = true
                executeProcess(process, input.toString())
            }
        } catch (_: TimeoutCancellationException) {
            return WorkBuddyResponse(
                if (submissionAttempted) ResponseKind.UNKNOWN_OUTCOME else ResponseKind.FAILURE,
            )
        } catch (cancelled: CancellationException) {
            if (submissionAttempted) {
                throw WorkBuddyUnknownOutcomeException(cancelled)
            }
            throw cancelled
        } catch (_: Exception) {
            return WorkBuddyResponse(
                if (submissionAttempted) ResponseKind.UNKNOWN_OUTCOME else ResponseKind.FAILURE,
            )
        } finally {
            withContext(NonCancellable) {
                runCatching { process?.close() }
            }
        }
        if (!result.successful) {
            val kind = when (result.exitCode) {
                EXIT_AUTHENTICATION -> ResponseKind.AUTHENTICATION
                EXIT_AUTHORIZATION -> ResponseKind.AUTHORIZATION
                EXIT_RATE_LIMIT -> ResponseKind.RATE_LIMIT
                EXIT_UNKNOWN_OUTCOME -> ResponseKind.UNKNOWN_OUTCOME
                else -> if (submissionAttempted) ResponseKind.UNKNOWN_OUTCOME else ResponseKind.FAILURE
            }
            return WorkBuddyResponse(kind)
        }
        return WorkBuddyResponse(ResponseKind.OK, rawBody = result.standardOutput)
    }

    private suspend fun executeProcess(
        process: RemoteDuplexProcess,
        input: String,
    ): RemoteCommandResult = coroutineScope {
        val stdout = async { process.standardOutputLines.collectBounded(MAX_RESPONSE_LENGTH) }
        val stderr = async { process.standardErrorLines.collectBounded(MAX_ERROR_LENGTH) }
        process.writeLine(input)
        val exitCode = process.exitCode.first { it != null } ?: error("Missing WorkBuddy helper exit code")
        RemoteCommandResult(exitCode, stdout.await(), stderr.await())
    }

    private companion object {
        const val OPERATION_STATUS = "status"
        const val OPERATION_HISTORY = "history"
        const val OPERATION_SEND = "send"
        const val EXIT_AUTHENTICATION = 41
        const val EXIT_AUTHORIZATION = 42
        const val EXIT_RATE_LIMIT = 43
        const val EXIT_UNKNOWN_OUTCOME = 44
        val REQUEST_DEADLINE = 20.seconds
        val JSON = Json { isLenient = false }
        const val MAX_RESPONSE_LENGTH = 1_048_576
        const val MAX_ERROR_LENGTH = 16_384

        // The endpoint identity and paths are fixed to the pinned official v2 contract. OAuth
        // material, consent, scopes, and message bodies never enter argv or command output. The
        // bounded request envelope travels over stdin so SSH command encoding cannot expose it.
        const val HTTP_HELPER = """import json,os,sys,urllib.error,urllib.parse,urllib.request
op=sys.argv[1]
line=sys.stdin.readline(262145)
if not line or len(line)>262144: sys.exit(45)
try: params=json.loads(line)
except (TypeError,ValueError): sys.exit(45)
if not isinstance(params,dict): sys.exit(45)
if os.environ.get('AGENT_RELAY_WORKBUDDY_CONSENT')!='enabled': sys.exit(41)
token=os.environ.get('AGENT_RELAY_WORKBUDDY_ACCESS_TOKEN','')
scopes=set(os.environ.get('AGENT_RELAY_WORKBUDDY_SCOPES','').split())
# The pinned page spells this scope "invokable"; AR-2212 prose used "invocable".
required='user.localassistant.invokable' if op=='send' else 'user.localassistant.readable'
if not token: sys.exit(41)
allowed={'user.localassistant.readable','user.localassistant.invokable'}
if required not in scopes or not scopes.issubset(allowed): sys.exit(42)
base='https://www.workbuddy.cn/openapi/v2/localassistant'
method='GET'; body=None
if op=='history':
 limit=params.get('limit'); offset=params.get('offset')
 if type(limit) is not int or not 1<=limit<=100 or type(offset) is not int or not 0<=offset<=1000000: sys.exit(45)
 q=urllib.parse.urlencode({'limit':limit,'offset':offset}); url=base+'/message?'+q
elif op=='send':
 content=params.get('content')
 if not isinstance(content,str) or not content.strip() or len(content)>32768: sys.exit(45)
 method='POST'; body=json.dumps({'content':content,'msg_type':'text'},separators=(',',':')).encode('utf-8'); url=base+'/message'
elif op=='status': url=base
else: sys.exit(45)
headers={'Authorization':'Bearer '+token,'Accept':'application/json'}
if body is not None: headers['Content-Type']='application/json'
request=urllib.request.Request(url,data=body,headers=headers,method=method)
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,request,fp,code,msg,headers,newurl): return None
opener=urllib.request.build_opener(NoRedirect)
try:
 with opener.open(request,timeout=15) as response:
  payload=response.read(1048577)
  if len(payload)>1048576: sys.exit(45)
  sys.stdout.write(payload.decode('utf-8'))
except urllib.error.HTTPError as failure:
 code={401:41,403:42,429:43}.get(failure.code)
 sys.exit(code if code is not None else (44 if op=='send' and failure.code>=500 else 45))
except (TimeoutError,urllib.error.URLError):
 sys.exit(44 if op=='send' else 45)
"""
    }
}

private data class WorkBuddyResponse(
    val kind: ResponseKind,
    val rawBody: String = "",
    val root: JsonObject = JsonObject(emptyMap()),
) {
    val body: JsonObject
        get() = root
}

private enum class ResponseKind {
    OK,
    AUTHENTICATION,
    AUTHORIZATION,
    RATE_LIMIT,
    UNKNOWN_OUTCOME,
    FAILURE,
}

private fun JsonElement.requireObject(label: String): JsonObject =
    this as? JsonObject ?: error("Invalid WorkBuddy $label")

private fun JsonElement.requireString(label: String): String =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid WorkBuddy $label")

private fun JsonObject.string(name: String): String =
    get(name)?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
        ?: error("Invalid WorkBuddy $name")

private fun JsonObject.number(name: String): Long =
    get(name)?.let { it as? JsonPrimitive }?.takeUnless { it.isString }?.content?.toLongOrNull()
        ?: error("Invalid WorkBuddy $name")

private fun JsonObject.boolean(name: String): Boolean =
    get(name)?.let { it as? JsonPrimitive }?.takeUnless { it.isString }?.content?.toBooleanStrictOrNull()
        ?: error("Invalid WorkBuddy $name")

private fun JsonObject.array(name: String): JsonArray =
    get(name) as? JsonArray ?: error("Invalid WorkBuddy $name")

private fun JsonObject.requireData(): JsonObject =
    (get("data") ?: error("Invalid WorkBuddy data")).requireObject("data")

private fun String.requireOpaqueId(label: String): String = also {
    require(matches(Regex("[A-Za-z0-9._:-]{1,128}"))) { "Invalid WorkBuddy $label" }
}

private suspend fun Flow<String>.collectBounded(maximumLength: Int): String {
    val result = StringBuilder()
    collect { line ->
        val separatorLength = if (result.isEmpty()) 0 else 1
        require(result.length + separatorLength + line.length <= maximumLength) {
            "WorkBuddy helper output is too large"
        }
        if (separatorLength != 0) result.append('\n')
        result.append(line)
    }
    return result.toString()
}

internal const val MAX_HISTORY_PAGE_SIZE = 100
internal const val MAX_HISTORY_OFFSET = 1_000_000
internal const val MAX_MESSAGE_LENGTH = 32_768
internal const val MAX_CONTENT_PARTS = 16
