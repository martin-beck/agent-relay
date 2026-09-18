/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.api

import dev.agentrelay.provider.api.RemoteAgentRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class SshConnectionManager(
    private val profileStore: SshProfileStore,
    credentialStore: SshCredentialStore,
    private val hostKeyStore: SshHostKeyStore,
    private val connector: SshConnector,
    private val reconnectPolicy: SshReconnectPolicy = SshReconnectPolicy(),
    // One application-level liveness probe is sufficient. The transport connector does not
    // also emit a second fixed-rate keepalive by default (see JschSshConnector).
    private val heartbeatInterval: Duration = 5.minutes,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: SshClock = SshClock(System::currentTimeMillis),
    private val sleeper: SshDelay = SshDelay { delay(it) },
    private val random: SshRandom = SshRandom(Random.Default::nextDouble),
) : Closeable {
    private val routeResolver = SshConnectionRouteResolver(
        profiles = profileStore,
        credentialStore = credentialStore,
        hostKeys = hostKeyStore,
    )
    private val managerJob = SupervisorJob()
    private val scope = CoroutineScope(managerJob + dispatcher)
    private val sessions = ConcurrentHashMap<SshProfileId, ManagedSession>()

    init {
        require(heartbeatInterval.isPositive()) { "Heartbeat interval must be positive" }
    }

    fun session(profileId: SshProfileId): SshManagedSession =
        sessions.computeIfAbsent(profileId) { ManagedSession(it) }

    fun activeSessions(): List<SshManagedSession> = sessions.values.sortedBy { it.profileId.value }

    override fun close() {
        sessions.values.forEach { it.closeImmediately() }
        sessions.clear()
        scope.cancel()
    }

    private inner class ManagedSession(
        override val profileId: SshProfileId,
    ) : SshManagedSession {
        private val monitor = Any()
        private val mutableState = MutableStateFlow<SshConnectionState>(
            SshConnectionState.Disconnected(
                reason = SshDisconnectReason.NOT_CONNECTED,
                atEpochMillis = clock.epochMillis(),
            ),
        )
        private var connection: SshTransportConnection? = null
        private var loopJob: Job? = null
        private var wantsConnection = false
        private var backgroundSuspended = false
        private var endpointForDiagnostics = "unresolved"

        override val state: StateFlow<SshConnectionState> = mutableState.asStateFlow()

        override fun runtimeOrNull(): RemoteAgentRuntime? = synchronized(monitor) {
            connection?.takeIf { it.isConnected }?.runtime
        }

        override fun connect() {
            synchronized(monitor) {
                wantsConnection = true
                backgroundSuspended = false
                val running = loopJob
                if (running?.isActive == true && mutableState.value !is SshConnectionState.Failed) {
                    return
                }
                running?.cancel()
                loopJob = scope.launch { connectionLoop() }
            }
        }

        override suspend fun approveHostKey(challenge: SshHostKeyChallenge): Boolean {
            if (challenge.disposition != SshHostKeyDisposition.UNKNOWN || !isCurrentChallenge(challenge)) {
                return false
            }
            val trusted = hostKeyStore.trustFirstUse(challenge.candidate)
            if (trusted) {
                restartConnectionLoop()
            }
            return trusted
        }

        override suspend fun replaceChangedHostKey(challenge: SshHostKeyChallenge): Boolean {
            if (challenge.disposition != SshHostKeyDisposition.CHANGED || !isCurrentChallenge(challenge)) {
                return false
            }
            val replaced = hostKeyStore.replace(
                candidate = challenge.candidate,
                expectedFingerprints = challenge.trustedFingerprints.toSet(),
            )
            if (replaced) {
                restartConnectionLoop()
            }
            return replaced
        }

        override suspend fun disconnect() {
            stop(
                reason = SshDisconnectReason.USER_REQUESTED,
                markBackgroundSuspended = false,
            )
        }

        override suspend fun suspendForBackground() {
            stop(
                reason = SshDisconnectReason.ANDROID_BACKGROUND_SUSPENDED,
                markBackgroundSuspended = true,
            )
        }

        override fun resumeFromBackground() {
            val shouldResume = synchronized(monitor) {
                if (!backgroundSuspended) {
                    false
                } else {
                    backgroundSuspended = false
                    true
                }
            }
            if (shouldResume) {
                connect()
            }
        }

        override fun diagnosticSnapshot(): SshSessionSnapshot = SshSessionSnapshot(
            profileId = profileId,
            endpoint = synchronized(monitor) { endpointForDiagnostics },
            state = state.value,
        )

        fun closeImmediately() {
            val toClose = synchronized(monitor) {
                wantsConnection = false
                loopJob?.cancel()
                loopJob = null
                connection.also { connection = null }
            }
            toClose?.close()
            mutableState.value = SshConnectionState.Disconnected(
                reason = SshDisconnectReason.USER_REQUESTED,
                atEpochMillis = clock.epochMillis(),
            )
        }

        private suspend fun stop(
            reason: SshDisconnectReason,
            markBackgroundSuspended: Boolean,
        ) {
            val (job, toClose) = synchronized(monitor) {
                wantsConnection = false
                backgroundSuspended = markBackgroundSuspended
                Pair(loopJob.also { loopJob = null }, connection.also { connection = null })
            }
            job?.cancelAndJoin()
            toClose?.close()
            mutableState.value = SshConnectionState.Disconnected(
                reason = reason,
                atEpochMillis = clock.epochMillis(),
            )
        }

        private fun isCurrentChallenge(challenge: SshHostKeyChallenge): Boolean =
            (state.value as? SshConnectionState.AwaitingHostKeyTrust)?.challenge == challenge

        private fun restartConnectionLoop() {
            synchronized(monitor) {
                wantsConnection = true
                backgroundSuspended = false
                loopJob?.cancel()
                loopJob = scope.launch { connectionLoop() }
            }
        }

        private suspend fun connectionLoop() {
            val currentJob = currentCoroutineContext()[Job]
            var retryAttempt = 0
            try {
                while (currentCoroutineContext().isActive && wantsConnection()) {
                    val profile = profileStore.profile(profileId)
                    if (profile == null) {
                        fail(
                            SshFailure(
                                category = SshFailureCategory.CONFIGURATION,
                                code = "SSH_PROFILE_MISSING",
                                actionableMessage = "The SSH profile no longer exists.",
                                recoverable = false,
                            ),
                        )
                        return
                    }
                    synchronized(monitor) {
                        endpointForDiagnostics = profile.endpoint.displayName
                    }

                    val failure = tryConnect(profile, retryAttempt + 1)
                    if (failure == null || !wantsConnection()) {
                        return
                    }
                    if (!failure.recoverable || retryAttempt >= reconnectPolicy.retryLimit) {
                        fail(
                            if (failure.recoverable) {
                                failure.copy(
                                    code = "SSH_RETRY_LIMIT_REACHED",
                                    actionableMessage =
                                    "The SSH host is still unreachable after bounded retries. Retry when connectivity returns.",
                                    recoverable = true,
                                )
                            } else {
                                failure
                            },
                        )
                        return
                    }

                    retryAttempt += 1
                    val reconnectDelay = reconnectPolicy.delayForAttempt(
                        retryAttempt,
                        random.nextUnitDouble(),
                    )
                    mutableState.value = SshConnectionState.Reconnecting(
                        attempt = retryAttempt,
                        delay = reconnectDelay,
                        retryAtEpochMillis = clock.epochMillis() + reconnectDelay.inWholeMilliseconds,
                        lastFailure = failure,
                    )
                    sleeper.wait(reconnectDelay)
                }
            } finally {
                synchronized(monitor) {
                    if (loopJob === currentJob) {
                        loopJob = null
                    }
                }
            }
        }

        private suspend fun tryConnect(profile: SshProfile, attempt: Int): SshFailure? {
            val route = try {
                routeResolver.resolve(profile)
            } catch (failure: Throwable) {
                return failure.toSshFailure()
            }

            var opened: SshTransportConnection? = null
            return try {
                val startedAt = clock.epochMillis()
                setConnecting(attempt, SshConnectPhase.OPENING_SOCKET, startedAt)
                opened = connector.connect(
                    route = route,
                    phaseListener = SshConnectPhaseListener { phase ->
                        setConnecting(attempt, phase, startedAt)
                    },
                )
                currentCoroutineContext().ensureActive()
                if (!opened.isConnected) {
                    throw SshConnectionException(
                        SshFailure(
                            category = SshFailureCategory.NETWORK,
                            code = "SSH_DISCONNECTED_DURING_CONNECT",
                            actionableMessage = "The SSH connection closed before it became ready.",
                            recoverable = true,
                        ),
                    )
                }
                synchronized(monitor) {
                    connection = opened
                }
                val connectedAt = clock.epochMillis()
                mutableState.value = SshConnectionState.Connected(
                    connectedAtEpochMillis = connectedAt,
                    lastHeartbeatAtEpochMillis = null,
                    lastLatency = null,
                )
                monitorConnection(opened, connectedAt)
                SshFailure(
                    category = SshFailureCategory.NETWORK,
                    code = "SSH_CONNECTION_LOST",
                    actionableMessage = "The SSH connection was lost. Reconnecting automatically.",
                    recoverable = true,
                )
            } catch (approval: SshHostKeyApprovalRequiredException) {
                synchronized(monitor) {
                    wantsConnection = false
                }
                mutableState.value = SshConnectionState.AwaitingHostKeyTrust(
                    challenge = approval.challenge,
                    atEpochMillis = clock.epochMillis(),
                )
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                failure.toSshFailure()
            } finally {
                route.close()
                synchronized(monitor) {
                    if (connection === opened) {
                        connection = null
                    }
                }
                opened?.close()
            }
        }

        private suspend fun monitorConnection(
            opened: SshTransportConnection,
            connectedAt: Long,
        ) {
            while (wantsConnection() && opened.isConnected) {
                sleeper.wait(heartbeatInterval)
                currentCoroutineContext().ensureActive()
                val latency = opened.heartbeat()
                mutableState.value = SshConnectionState.Connected(
                    connectedAtEpochMillis = connectedAt,
                    lastHeartbeatAtEpochMillis = clock.epochMillis(),
                    lastLatency = latency,
                )
            }
        }

        private fun setConnecting(
            attempt: Int,
            phase: SshConnectPhase,
            startedAt: Long,
        ) {
            if (wantsConnection()) {
                mutableState.value = SshConnectionState.Connecting(
                    attempt = attempt,
                    phase = phase,
                    startedAtEpochMillis = startedAt,
                )
            }
        }

        private fun fail(failure: SshFailure) {
            synchronized(monitor) {
                wantsConnection = false
            }
            mutableState.value = SshConnectionState.Failed(
                failure = failure,
                atEpochMillis = clock.epochMillis(),
            )
        }

        private fun wantsConnection(): Boolean = synchronized(monitor) { wantsConnection }
    }
}
