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
    hostId: String,
    channelConnectTimeout: Duration,
    dispatcher: CoroutineDispatcher,
) : SshTransportConnection {
    override val runtime = JschRemoteAgentRuntime(
        session = session,
        hostId = hostId,
        channelConnectTimeout = channelConnectTimeout,
        dispatcher = dispatcher,
    )

    override val isConnected: Boolean
        get() = session.isConnected

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
        session.disconnect()
    }
}
