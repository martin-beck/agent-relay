/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.jsch

import java.util.Arrays
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JschPasswordUserInfoTest {
    @Test
    fun keyboardInteractiveRespondsOnlyToOneHiddenPasswordPrompt() {
        val password = "test-password".encodeToByteArray()
        try {
            val userInfo = JschSshConnector.JschPasswordUserInfo(password)
            assertTrue(userInfo.promptPassword("Password for user@example.test"))
            assertFalse(userInfo.promptPassword("Verification code for user@example.test"))
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
