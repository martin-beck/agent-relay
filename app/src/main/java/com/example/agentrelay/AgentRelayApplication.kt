package com.example.agentrelay

import android.app.Application
import android.content.Context
import com.example.agentrelay.data.CoordinatorSessionHubRuntime
import com.example.agentrelay.data.SessionHubRuntime
import dev.agentrelay.connection.api.ConnectionProviderRegistry
import dev.agentrelay.connection.local.LocalConnectionProvider
import dev.agentrelay.provider.aider.AiderAgentProviderFactory
import dev.agentrelay.provider.api.AgentProviderRegistry
import dev.agentrelay.provider.claude.ClaudeAgentProviderFactory
import dev.agentrelay.provider.clinecli.ClineAgentProviderFactory
import dev.agentrelay.provider.codex.CodexAgentProviderFactory
import dev.agentrelay.provider.continuecli.ContinueAgentProviderFactory
import dev.agentrelay.provider.opencode.OpenCodeAgentProviderFactory
import dev.agentrelay.session.android.AndroidEncryptedSessionHubStore
import dev.agentrelay.session.api.PersistentSessionHubRepository
import dev.agentrelay.session.runtime.SessionCoordinator
import dev.agentrelay.ssh.android.AndroidSshConnectionEnvironment
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AgentRelayApplication : Application() {
    internal val graph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AgentRelayGraph(applicationContext)
    }
}

internal class AgentRelayGraph(private val context: Context) {
    private val initializationMutex = Mutex()

    @Volatile
    private var sessionRuntime: SessionHubRuntime? = null

    suspend fun sessionHubRuntime(): SessionHubRuntime {
        sessionRuntime?.let { return it }
        return initializationMutex.withLock {
            sessionRuntime ?: createSessionRuntime().also { sessionRuntime = it }
        }
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
            )
        } catch (failure: Throwable) {
            connections.close()
            throw failure
        }
    }

    private companion object {
        const val LOCAL_WORKSPACE_DIRECTORY = "agent-workspaces"
    }
}
