/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.jsch

import dev.agentrelay.provider.api.RemoteCommand

internal object PosixCommandEncoder {
    fun encode(command: RemoteCommand): String = buildString {
        command.workingDirectory?.let {
            append("cd ")
            append(quote(it))
            append(" && ")
        }
        append("exec ")
        if (command.environment.isNotEmpty()) {
            append("env ")
            command.environment.toSortedMap().forEach { (name, value) ->
                append(name)
                append('=')
                append(quote(value))
                append(' ')
            }
        }
        append(quote(command.program))
        command.arguments.forEach {
            append(' ')
            append(quote(it))
        }
    }

    fun quote(value: String): String {
        require('\u0000' !in value) { "Remote command values must not contain NUL" }
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
