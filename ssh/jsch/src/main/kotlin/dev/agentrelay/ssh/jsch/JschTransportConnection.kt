package dev.agentrelay.ssh.jsch

import com.jcraft.jsch.Session
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.ssh.api.SshConnectionException
import dev.agentrelay.ssh.api.SshFailure
import dev.agentrelay.ssh.api.SshFailureCategory
import dev.agentrelay.ssh.api.SshTransportConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

internal class JschTransportConnection(
    private val session: Session,
    routeSessions: List<Session> = listOf(session),
    hostId: String,
    channelConnectTimeout: Duration,
    dispatcher: CoroutineDispatcher,
) : SshTransportConnection {
    private val routeSessions = routeSessions.toList()

    init {
        require(this.routeSessions.isNotEmpty() && this.routeSessions.last() === session) {
            "The destination must be the final SSH route session"
        }
    }

    override val runtime = JschRemoteAgentRuntime(
        session = session,
        hostId = hostId,
        channelConnectTimeout = channelConnectTimeout,
        dispatcher = dispatcher,
    )

    override val isConnected: Boolean
        get() = routeSessions.all(Session::isConnected)

    override suspend fun heartbeat(): Duration {
        val elapsed = measureTime {
            val result = runtime.execute(
                command = RemoteCommand("true"),
                timeout = 5.seconds,
            )
            if (!result.successful) {
                throw SshConnectionException(
                    SshFailure(
                        category = SshFailureCategory.NETWORK,
                        code = "SSH_HEARTBEAT_FAILED",
                        actionableMessage =
                        "The SSH heartbeat failed. Reconnecting automatically.",
                        recoverable = true,
                    ),
                )
            }
        }
        return elapsed
    }

    override fun close() {
        routeSessions.asReversed().forEach(Session::disconnect)
    }
}
