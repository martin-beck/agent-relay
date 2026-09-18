/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.jsch

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class JschSshConnectorConfigurationTest {
    @Test
    fun transportKeepaliveCanBeDisabledToAvoidDuplicateIdleWakeups() {
        JschSshConnector(serverAliveInterval = Duration.ZERO)
    }

    @Test
    fun transportKeepaliveRejectsNegativeIntervals() {
        assertFailsWith<IllegalArgumentException> {
            JschSshConnector(serverAliveInterval = (-1).seconds)
        }
    }
}
