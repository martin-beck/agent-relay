package com.example.agentrelay

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.agentrelay.background.AndroidBackgroundTransportStarter
import com.example.agentrelay.background.BackgroundConnectionLease
import com.example.agentrelay.background.BackgroundTransportController
import com.example.agentrelay.background.configuredBackgroundRecoveryConnections
import com.example.agentrelay.data.CoordinatorSessionHubRuntime
import com.example.agentrelay.data.BackgroundAwareSessionHubRuntime
import com.example.agentrelay.data.SessionHubRuntime
import com.example.agentrelay.diagnostics.DiagnosticRuntimeConfig
import com.example.agentrelay.diagnostics.DiagnosticTrace
import com.example.agentrelay.notifications.AndroidSessionNotificationSink
import com.example.agentrelay.notifications.SessionNotificationRuntime
import dev.agentrelay.connection.api.ConnectionProviderRegistry
import dev.agentrelay.connection.local.LocalConnectionProvider
import dev.agentrelay.provider.aider.AiderAgentProviderFactory
import dev.agentrelay.provider.api.AgentProviderRegistry
import dev.agentrelay.provider.claude.ClaudeAgentProviderFactory
import dev.agentrelay.provider.clinecli.ClineAgentProviderFactory
import dev.agentrelay.provider.codex.CodexAgentProviderFactory
import dev.agentrelay.provider.continuecli.ContinueAgentProviderFactory
import dev.agentrelay.provider.opencode.OpenCodeAgentProviderFactory
import dev.agentrelay.provider.opendesk.OpenDeskAgentProviderFactory
import dev.agentrelay.session.android.AndroidEncryptedSessionHubStore
import dev.agentrelay.session.api.PersistentSessionHubRepository
import dev.agentrelay.session.runtime.SessionCoordinator
import dev.agentrelay.ssh.android.AndroidSshConnectionEnvironment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AgentRelayApplication : Application() {
    internal val diagnosticConfig: DiagnosticRuntimeConfig = DiagnosticRuntimeConfig.fromBuildConfig()
    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val graphDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentRelayGraph(applicationContext, lifecycleScope)
    }
    internal val graph by graphDelegate
    internal val backgroundTransport by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BackgroundTransportController(
            AndroidBackgroundTransportStarter(applicationContext),
        )
    }

    private val backgroundLifecycle = AppBackgroundLifecycleObserver(
        scope = lifecycleScope,
        enterForeground = { graph.resumeFromBackgroundIfInitialized() },
        enterBackground = { graph.suspendForBackgroundIfInitialized() },
        onFailure = {
            Log.e(LOG_TAG, "Connection background transition failed")
        },
    )

    override fun onCreate() {
        super.onCreate()
        DiagnosticTrace.section(diagnosticConfig, "startup") {
            ProcessLifecycleOwner.get().lifecycle.addObserver(backgroundLifecycle)
        }
    }

    internal fun releaseBackgroundTransportAfterServiceDestruction() {
        if (!graphDelegate.isInitialized()) {
            return
        }
        lifecycleScope.launch {
            try {
                graph.disableBackgroundTransportIfInitialized()
            } catch (_: Throwable) {
                Log.e(LOG_TAG, "Background connection cleanup failed")
            }
        }
    }

    private companion object {
        const val LOG_TAG = "AgentRelay"
    }
}

internal class AgentRelayGraph(
    private val context: Context,
    notificationScope: CoroutineScope,
) {
    private val initializationMutex = Mutex()
    private val notificationRuntime = SessionNotificationRuntime(
        scope = notificationScope,
        sink = AndroidSessionNotificationSink(context),
        onFailure = { Log.e(LOG_TAG, "Notification state transition failed") },
    )
    private val backgroundConnectionLease = BackgroundConnectionLease(context)

    @Volatile
    private var sessionRuntime: SessionHubRuntime? = null
    private var notificationAttached = false
    private var appForeground = true
    private var backgroundTransportActive = false
    private var connectionsSuspended = false

    suspend fun sessionHubRuntime(): SessionHubRuntime {
        return initializationMutex.withLock {
            val runtime = sessionRuntime ?: createSessionRuntime().also { created ->
                sessionRuntime = created
            }
            if (!notificationAttached) {
                applyConnectionLifetime(runtime)
                notificationRuntime.attach(
                    snapshots = runtime.sessionSnapshot,
                    initiallyForeground = appForeground,
                )
                notificationAttached = true
            } else {
                applyConnectionLifetime(runtime)
            }
            runtime
        }
    }

    suspend fun suspendForBackgroundIfInitialized() {
        initializationMutex.withLock {
            appForeground = false
            val active = sessionRuntime
            if (active != null) {
                applyConnectionLifetime(active)
                notificationRuntime.enterBackground()
            }
        }
    }

    suspend fun resumeFromBackgroundIfInitialized() {
        initializationMutex.withLock {
            appForeground = true
            val active = sessionRuntime
            if (active != null) {
                notificationRuntime.enterForeground()
                applyConnectionLifetime(active)
            }
        }
    }

    suspend fun enableBackgroundTransport(recoverAfterProcessDeath: Boolean) {
        val recoveryConnections = if (recoverAfterProcessDeath) {
            backgroundConnectionLease.restore()
        } else {
            null
        }
        initializationMutex.withLock {
            backgroundTransportActive = true
        }
        val runtime = sessionHubRuntime()
        if (recoveryConnections == null) {
            backgroundConnectionLease.enable()
            return
        }

        runtime.refreshProfiles()
        configuredBackgroundRecoveryConnections(
            desiredConnections = recoveryConnections,
            profiles = runtime.coordinatorSnapshot.value.profiles,
        )
            .forEach { runtime.connect(it) }
    }

    suspend fun disableBackgroundTransportIfInitialized() {
        initializationMutex.withLock {
            backgroundTransportActive = false
            sessionRuntime?.let { active ->
                applyConnectionLifetime(active)
            }
        }
        backgroundConnectionLease.disable()
    }

    suspend fun retryNotificationsIfInitialized() {
        initializationMutex.withLock {
            if (sessionRuntime != null) {
                notificationRuntime.retryCurrent()
            }
        }
    }

    private suspend fun applyConnectionLifetime(runtime: SessionHubRuntime) {
        val shouldSuspend = shouldSuspendProviderConnections(
            appForeground = appForeground,
            backgroundTransportActive = backgroundTransportActive,
        )
        if (connectionsSuspended == shouldSuspend) {
            return
        }
        val backgroundAware = runtime as? BackgroundAwareSessionHubRuntime ?: return
        if (shouldSuspend) {
            backgroundAware.suspendForBackground()
        } else {
            backgroundAware.resumeFromBackground()
        }
        connectionsSuspended = shouldSuspend
    }

    private suspend fun createSessionRuntime(): SessionHubRuntime {
        val localWorkspace = File(context.filesDir, LOCAL_WORKSPACE_DIRECTORY)
        check(localWorkspace.isDirectory || localWorkspace.mkdirs()) {
            "The local agent workspace could not be prepared"
        }

        val sshEnvironment = AndroidSshConnectionEnvironment.create(context)
        val connections = try {
            ConnectionProviderRegistry(
                listOf(
                    LocalConnectionProvider(workingRoot = localWorkspace),
                    sshEnvironment.provider,
                ),
            )
        } catch (failure: Throwable) {
            sshEnvironment.close()
            throw failure
        }

        return try {
            val agents = AgentProviderRegistry(
                listOf(
                    AiderAgentProviderFactory(),
                    ClaudeAgentProviderFactory(),
                    ClineAgentProviderFactory(),
                    CodexAgentProviderFactory(),
                    ContinueAgentProviderFactory(),
                    OpenCodeAgentProviderFactory(),
                    OpenDeskAgentProviderFactory(),
                ),
            )
            val repository = PersistentSessionHubRepository.open(
                AndroidEncryptedSessionHubStore(context),
            )
            val coordinator = SessionCoordinator(
                connectionRegistry = connections,
                agentRegistry = agents,
                repository = repository,
            )
            CoordinatorSessionHubRuntime(
                coordinator = coordinator,
                connections = connections,
                connectionProviders = connections.descriptors(),
                onConnectRequested = backgroundConnectionLease::recordConnect,
                onDisconnectRequested = backgroundConnectionLease::recordDisconnect,
            )
        } catch (failure: Throwable) {
            connections.close()
            throw failure
        }
    }

    private companion object {
        const val LOCAL_WORKSPACE_DIRECTORY = "agent-workspaces"
        const val LOG_TAG = "AgentRelay"
    }
}

internal fun shouldSuspendProviderConnections(
    appForeground: Boolean,
    backgroundTransportActive: Boolean,
): Boolean = !appForeground && !backgroundTransportActive
