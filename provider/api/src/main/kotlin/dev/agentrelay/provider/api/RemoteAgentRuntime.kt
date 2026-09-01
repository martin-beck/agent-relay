package dev.agentrelay.provider.api

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class RemoteCommand(
    val program: String,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
) {
    init {
        require(program.isNotBlank()) { "Program must not be blank" }
        require(environment.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) {
            "Environment variable names must be portable shell identifiers"
        }
    }
}

data class RemoteCommandResult(val exitCode: Int, val standardOutput: String, val standardError: String) {
    val successful: Boolean
        get() = exitCode == 0
}

interface RemoteDuplexProcess {
    val standardOutputLines: Flow<String>
    val standardErrorLines: Flow<String>
    val exitCode: StateFlow<Int?>

    suspend fun writeLine(line: String)

    suspend fun close()
}

interface RemoteAgentRuntime {
    val hostId: String
    val fileAccess: RemoteFileAccess?
        get() = null

    suspend fun execute(command: RemoteCommand, timeout: Duration = 15.seconds): RemoteCommandResult

    suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess
}
