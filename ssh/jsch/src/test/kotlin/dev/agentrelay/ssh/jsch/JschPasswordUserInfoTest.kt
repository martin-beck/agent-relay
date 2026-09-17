/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.jsch

import java.util.Arrays
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class JschPasswordUserInfoTest {
    @Test
    fun keyboardInteractiveRespondsOnlyToOneHiddenPasswordPrompt() {
        val password = "test-password".encodeToByteArray()
        try {
            val userInfo = JschSshConnector.JschPasswordUserInfo(password)
            assertContentEquals(
                arrayOf("test-password"),
                userInfo.promptKeyboardInteractive(
                    destination = "ssh",
                    name = "login",
                    instruction = "",
                    prompt = arrayOf("Password:"),
                    echo = booleanArrayOf(false),
                ),
            )
            assertNull(
                userInfo.promptKeyboardInteractive(
                    destination = "ssh",
                    name = "login",
                    instruction = "",
                    prompt = arrayOf("Verification code:"),
                    echo = booleanArrayOf(false),
                ),
            )
            assertNull(
                userInfo.promptKeyboardInteractive(
                    destination = "ssh",
                    name = "login",
                    instruction = "",
                    prompt = arrayOf("Password:", "Verification code:"),
                    echo = booleanArrayOf(false, false),
                ),
            )
            assertNull(
                userInfo.promptKeyboardInteractive(
                    destination = "ssh",
                    name = "login",
                    instruction = "",
                    prompt = arrayOf("Password:"),
                    echo = booleanArrayOf(true),
                ),
            )
        } finally {
            Arrays.fill(password, 0)
        }
    }

}
