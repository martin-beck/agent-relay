/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.testing

import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

private const val MAX_FRAME_CHARS = 16 * 1024
private const val MAX_OUTPUT_CHARS = 64 * 1024

data class ScriptedFrame(val text: String, val delay: Duration = Duration.ZERO) {
    init {
        require(text.length <= MAX_FRAME_CHARS)
        require(!delay.isNegative())
    }
}
data class ScriptedCommand(
    val command: RemoteCommand,
    val stdout: List<ScriptedFrame> = emptyList(),
    val stderr: List<ScriptedFrame> = emptyList(),
    val exitCode: Int = 0,
    val writes: List<String> = emptyList(),
    val closeExitCode: Int? = null,
) {
    init {
        require(stdout.sumOf { it.text.length } <= MAX_OUTPUT_CHARS)
        require(stderr.sumOf { it.text.length } <= MAX_OUTPUT_CHARS)
    }
}
class ScriptedRemoteAgentRuntime(
    scripts: List<ScriptedCommand>,
    override val hostId: String = "scripted-host",
) : RemoteAgentRuntime {
    private val remaining = ArrayDeque(scripts)
    private val ledger = mutableListOf<RemoteCommand>()
    private var closed = false
    override suspend fun execute(command: RemoteCommand, timeout: Duration): RemoteCommandResult {
        check(!closed) { "Scripted runtime is closed" }
        val script = next(command)
        return RemoteCommandResult(script.exitCode, collect(script.stdout), collect(script.stderr))
    }
    override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess {
        check(!closed) { "Scripted runtime is closed" }
        return ScriptedRemoteDuplexProcess(next(command))
    }
    fun assertComplete() {
        check(remaining.isEmpty()) { "Scripted runtime has unused expectations" }
    }
    fun commands(): List<RemoteCommand> = ledger.toList()
    private fun next(command: RemoteCommand): ScriptedCommand {
        val script = remaining.removeFirstOrNull() ?: error("Unexpected scripted command")
        check(script.command == command) { "Scripted command mismatch at index " + ledger.size }
        ledger += command
        return script
    }
    private suspend fun collect(frames: List<ScriptedFrame>): String {
        val builder = StringBuilder()
        frames.forEach { frame -> delay(frame.delay); builder.append(frame.text) }
        return builder.toString()
    }
}
private class ScriptedRemoteDuplexProcess(private val script: ScriptedCommand) : RemoteDuplexProcess {
    private val writesRemaining = ArrayDeque(script.writes)
    private val mutableExitCode = MutableStateFlow<Int?>(script.exitCode)
    private var closed = false
    override val standardOutputLines: Flow<String> = frames(script.stdout)
    override val standardErrorLines: Flow<String> = frames(script.stderr)
    override val exitCode = mutableExitCode
    override suspend fun writeLine(line: String) {
        check(!closed) { "Scripted process is closed" }
        val expected = writesRemaining.removeFirstOrNull() ?: error("Unexpected scripted write")
        check(expected == line) { "Scripted process write mismatch" }
    }
    override suspend fun close() {
        if (closed) return
        closed = true
        check(writesRemaining.isEmpty()) { "Scripted process has unused writes" }
        mutableExitCode.value = script.closeExitCode ?: script.exitCode
    }
    private fun frames(values: List<ScriptedFrame>): Flow<String> = flow {
        values.forEach { frame -> delay(frame.delay); emit(frame.text) }
    }
}
