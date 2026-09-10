/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.jsch

import dev.agentrelay.provider.api.RemoteCommand
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PosixCommandEncoderTest {
    @Test
    fun everyUserControlledValueIsShellQuoted() {
        val encoded = PosixCommandEncoder.encode(
            RemoteCommand(
                program = "tool name",
                arguments = listOf("--literal", "\$(touch /tmp/not-created)", "a'b", ""),
                environment = mapOf(
                    "Z_VALUE" to "semi; colon",
                    "A_VALUE" to "",
                ),
                workingDirectory = "/work/a'b",
            ),
        )

        assertEquals(
            "cd '/work/a'\\''b' && exec env " +
                "A_VALUE='' Z_VALUE='semi; colon' " +
                "'tool name' '--literal' '\$(touch /tmp/not-created)' 'a'\\''b' ''",
            encoded,
        )
    }

    @Test
    fun minimalCommandStillUsesExecAndQuotesTheProgram() {
        assertEquals(
            "exec 'true'",
            PosixCommandEncoder.encode(RemoteCommand("true")),
        )
    }

    @Test
    fun nulIsRejectedBeforeOpeningAChannel() {
        assertFailsWith<IllegalArgumentException> {
            PosixCommandEncoder.encode(
                RemoteCommand(
                    program = "tool",
                    arguments = listOf("bad\u0000value"),
                ),
            )
        }
    }
}
