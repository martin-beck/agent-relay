/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.jsch

import com.jcraft.jsch.IdentityRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import dev.agentrelay.ssh.api.ResolvedSshHost
import dev.agentrelay.ssh.api.ResolvedSshAuthentication
import dev.agentrelay.ssh.api.SshConnectPhase
import dev.agentrelay.ssh.api.SshConnectPhaseListener
import dev.agentrelay.ssh.api.SshConnectionRoute
import dev.agentrelay.ssh.api.SshConnectionException
import dev.agentrelay.ssh.api.SshConnector
import dev.agentrelay.ssh.api.SshFailure
import dev.agentrelay.ssh.api.SshFailureCategory
import dev.agentrelay.ssh.api.SshHostKeyApprovalRequiredException
import dev.agentrelay.ssh.api.SshTransportConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Arrays
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

fun interface JschAgentIdentityProvider {
    fun identitiesFor(keyId: String): IdentityRepository?
}

class JschSshConnector(
    private val agentIdentityProvider: JschAgentIdentityProvider? = null,
    private val connectTimeout: Duration = 15.seconds,
    private val channelConnectTimeout: Duration = 10.seconds,
    private val serverAliveInterval: Duration = 15.seconds,
    private val serverAliveCountMax: Int = 3,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : SshConnector {
    init {
        require(connectTimeout.isPositive()) { "SSH connect timeout must be positive" }
        require(channelConnectTimeout.isPositive()) { "SSH channel timeout must be positive" }
        require(serverAliveInterval.isPositive()) { "SSH server-alive interval must be positive" }
        require(serverAliveCountMax >= 1) { "SSH server-alive count must be positive" }
        require(connectTimeout.inWholeMilliseconds <= Int.MAX_VALUE)
        require(channelConnectTimeout.inWholeMilliseconds <= Int.MAX_VALUE)
        require(serverAliveInterval.inWholeMilliseconds <= Int.MAX_VALUE)
    }

    override suspend fun connect(
        route: SshConnectionRoute,
        phaseListener: SshConnectPhaseListener,
    ): SshTransportConnection = withContext(dispatcher) {
        val sessions = mutableListOf<Session>()
        try {
            route.jumpHosts.forEach { jumpHost ->
                sessions += connectHost(
                    host = jumpHost,
                    through = sessions.lastOrNull(),
                    phaseListener = phaseListener,
                )
            }
            val destination = connectHost(
                host = route.destination,
                through = sessions.lastOrNull(),
                phaseListener = phaseListener,
            )
            sessions += destination
            JschTransportConnection(
                session = destination,
                routeSessions = sessions,
                hostId = route.destination.profile.id.value,
                channelConnectTimeout = channelConnectTimeout,
                dispatcher = dispatcher,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            sessions.asReversed().forEach(Session::disconnect)
            throw cancelled
        } catch (failure: Throwable) {
            sessions.asReversed().forEach(Session::disconnect)
            throw failure
        }
    }

    private fun connectHost(
        host: ResolvedSshHost,
        through: Session?,
        phaseListener: SshConnectPhaseListener,
    ): Session {
        phaseListener.onPhase(SshConnectPhase.OPENING_SOCKET)
        val jsch = JSch()
        val repository = StrictHostKeyRepository(
            endpoint = host.profile.endpoint,
            trustedKeys = host.trustedHostKeys,
            nowEpochMillis = nowEpochMillis,
            onCheck = { trusted ->
                phaseListener.onPhase(SshConnectPhase.VERIFYING_HOST_KEY)
                if (trusted) {
                    phaseListener.onPhase(SshConnectPhase.AUTHENTICATING)
                }
            },
        )
        jsch.hostKeyRepository = repository
        val session = jsch.getSession(
            host.profile.username,
            host.profile.endpoint.host,
            host.profile.endpoint.port,
        )
        through?.let { session.setProxy(JschJumpHostProxy(it)) }
        val temporarySecrets = mutableListOf<ByteArray>()
        try {
            configureSession(
                jsch = jsch,
                session = session,
                authentication = host.authentication,
                temporarySecrets = temporarySecrets,
            )
            session.connect(connectTimeout.inWholeMilliseconds.toInt())
            return session
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            session.disconnect()
            throw cancelled
        } catch (failure: Throwable) {
            session.disconnect()
            repository.observedChallenge()?.let {
                throw SshHostKeyApprovalRequiredException(it)
            }
            throw failure.asConnectionException()
        } finally {
            temporarySecrets.forEach { Arrays.fill(it, 0) }
            temporarySecrets.clear()
        }
    }

    private fun configureSession(
        jsch: JSch,
        session: Session,
        authentication: ResolvedSshAuthentication,
        temporarySecrets: MutableList<ByteArray>,
    ) {
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("enable_strict_kex", "yes")
        session.userInfo = RejectingUserInfo
        session.setDaemonThread(true)
        session.setServerAliveInterval(serverAliveInterval.inWholeMilliseconds.toInt())
        session.setServerAliveCountMax(serverAliveCountMax)

        when (authentication) {
            is ResolvedSshAuthentication.Password -> {
                session.setConfig("PreferredAuthentications", "password")
                val password = authentication.password.copy().also(temporarySecrets::add)
                session.setPassword(password)
            }

            is ResolvedSshAuthentication.ImportedKey -> {
                session.setConfig("PreferredAuthentications", "publickey")
                val privateKey = authentication.privateKey.copy().also(temporarySecrets::add)
                val passphrase = authentication.passphrase?.copy()?.also(temporarySecrets::add)
                jsch.addIdentity(
                    "agent-relay-imported-key",
                    privateKey,
                    null,
                    passphrase,
                )
            }

            is ResolvedSshAuthentication.AgentBacked -> {
                session.setConfig("PreferredAuthentications", "publickey")
                val repository = agentIdentityProvider?.identitiesFor(authentication.keyId)
                    ?: throw SshConnectionException(
                        SshFailure(
                            category = SshFailureCategory.CONFIGURATION,
                            code = "SSH_AGENT_IDENTITY_UNAVAILABLE",
                            actionableMessage =
                            "The agent-backed SSH key is unavailable. Unlock or reselect the key and retry.",
                            recoverable = false,
                        ),
                    )
                session.setIdentityRepository(repository)
            }
        }
    }

    private fun Throwable.asConnectionException(): SshConnectionException {
        if (this is SshConnectionException) {
            return this
        }
        val message = generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        val networkCause = generateSequence(this) { it.cause }.any {
            it is ConnectException ||
                it is NoRouteToHostException ||
                it is SocketException ||
                it is SocketTimeoutException ||
                it is UnknownHostException ||
                it is InterruptedIOException
        }
        return when {
            message.contains("auth fail") ||
                message.contains("userauth fail") ||
                message.contains("auth cancel") ||
                message.contains("authentication cancel") ||
                message.contains("authentication failed") ->
                SshConnectionException(
                    SshFailure(
                        category = SshFailureCategory.AUTHENTICATION,
                        code = "SSH_AUTHENTICATION_FAILED",
                        actionableMessage =
                        "SSH authentication failed. Verify the username and selected credential.",
                        recoverable = false,
                    ),
                    this,
                )

            message.contains("invalid privatekey") ||
                message.contains("privatekey") && message.contains("invalid") ->
                SshConnectionException(
                    SshFailure(
                        category = SshFailureCategory.AUTHENTICATION,
                        code = "SSH_PRIVATE_KEY_INVALID",
                        actionableMessage =
                        "The imported SSH private key could not be read. Re-import a supported key.",
                        recoverable = false,
                    ),
                    this,
                )

            message.contains("algorithm negotiation fail") ||
                message.contains("no matching") ->
                SshConnectionException(
                    SshFailure(
                        category = SshFailureCategory.CONFIGURATION,
                        code = "SSH_ALGORITHM_INCOMPATIBLE",
                        actionableMessage =
                        "The SSH server does not offer a compatible modern cryptographic algorithm.",
                        recoverable = false,
                    ),
                    this,
                )

            networkCause || this is JSchException ->
                SshConnectionException(
                    SshFailure(
                        category = SshFailureCategory.NETWORK,
                        code = "SSH_NETWORK_FAILURE",
                        actionableMessage =
                        "The SSH host could not be reached or the connection was interrupted.",
                        recoverable = true,
                    ),
                    this,
                )

            else ->
                SshConnectionException(
                    SshFailure(
                        category = SshFailureCategory.UNKNOWN,
                        code = "SSH_TRANSPORT_FAILURE",
                        actionableMessage =
                        "The SSH transport failed unexpectedly. Review redacted diagnostics and retry.",
                        recoverable = true,
                    ),
                    this,
                )
        }
    }

    private object RejectingUserInfo : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null

        override fun getPassword(): String? = null

        override fun promptPassword(message: String): Boolean = false

        override fun promptPassphrase(message: String): Boolean = false

        override fun promptYesNo(message: String): Boolean = false

        override fun showMessage(message: String) = Unit

        override fun promptKeyboardInteractive(
            destination: String,
            name: String,
            instruction: String,
            prompt: Array<out String>,
            echo: BooleanArray,
        ): Array<String>? = null
    }
}
