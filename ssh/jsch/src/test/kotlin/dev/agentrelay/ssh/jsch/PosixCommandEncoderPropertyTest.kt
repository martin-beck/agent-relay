/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.jsch

import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PosixCommandEncoderPropertyTest {
    @Test
    fun quoteRoundTripsGeneratedUnicode() = runTest {
        checkAll(1_000, Arb.string(0..256)) { value ->
            assertPosixQuoteRoundTrips(value)
        }
    }
}
