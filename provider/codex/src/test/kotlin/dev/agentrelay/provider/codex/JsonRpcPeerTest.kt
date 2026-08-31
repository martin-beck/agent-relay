package dev.agentrelay.provider.codex

import dev.agentrelay.provider.api.RemoteDuplexProcess
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JsonRpcPeerTest {
    @Test
    fun requestCorrelatesResponseById() = runTest {
        val process = FakeProcess()
        val peer = JsonRpcPeer(
            process = process,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            requestTimeout = 5.seconds,
        )

        val result = async { peer.request("thread/list") }
        val request = Json.parseToJsonElement(process.writes.receive()).jsonObject
        val id = request.getValue("id")
        process.output.emit("""{"jsonrpc":"2.0","id":$id,"result":{"data":[]}}""")

        assertEquals("thread/list", request["method"]?.asString())
        assertEquals("[]", result.await().jsonObject["data"].toString())
        peer.close()
    }

    @Test
    fun requestSurfacesStructuredProtocolErrors() = runTest {
        val process = FakeProcess()
        val peer = JsonRpcPeer(
            process = process,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            requestTimeout = 5.seconds,
        )

        val result = async { runCatching { peer.request("missing/method") } }
        val request = Json.parseToJsonElement(process.writes.receive()).jsonObject
        val id = request.getValue("id")
        process.output.emit(
            """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"Method not found"}}""",
        )

        val error = result.await().exceptionOrNull() as JsonRpcException
        assertEquals(-32601, error.code)
        assertEquals("Method not found", error.message)
        peer.close()
    }

    @Test
    fun serverRequestsAreExposedToTheConnection() = runTest {
        val process = FakeProcess()
        val peer = JsonRpcPeer(
            process = process,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val call = async(start = CoroutineStart.UNDISPATCHED) { peer.calls.first() }

        process.output.emit(
            """
            {
              "jsonrpc": "2.0",
              "id": "approval-1",
              "method": "item/fileChange/requestApproval",
              "params": {
                "threadId": "thread-1"
              }
            }
            """.trimIndent(),
        )

        val received = call.await()
        assertEquals("item/fileChange/requestApproval", received.method)
        assertEquals(JsonPrimitive("approval-1"), received.id)
        peer.close()
    }

    private class FakeProcess : RemoteDuplexProcess {
        val output = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val errors = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val writes = Channel<String>(Channel.UNLIMITED)
        private val mutableExitCode = MutableStateFlow<Int?>(null)

        override val standardOutputLines: Flow<String> = output
        override val standardErrorLines: Flow<String> = errors
        override val exitCode = mutableExitCode

        override suspend fun writeLine(line: String) {
            writes.send(line)
        }

        override suspend fun close() {
            mutableExitCode.value = 0
            writes.close()
        }
    }
}
