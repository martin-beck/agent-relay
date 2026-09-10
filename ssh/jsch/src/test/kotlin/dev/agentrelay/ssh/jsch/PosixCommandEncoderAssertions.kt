/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.jsch

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal fun assertPosixQuoteRoundTrips(value: String) {
    if ('\u0000' in value) {
        assertFailsWith<IllegalArgumentException> {
            PosixCommandEncoder.quote(value)
        }
        return
    }

    val encoded = PosixCommandEncoder.quote(value)
    assertTrue(encoded.startsWith("'"))

    val decoded = buildString {
        var index = 1
        while (true) {
            val closingQuote = encoded.indexOf('\'', index)
            assertTrue(closingQuote >= 0, "Encoded value has no closing quote")
            append(encoded, index, closingQuote)
            index = closingQuote + 1
            if (index == encoded.length) {
                break
            }
            assertTrue(
                encoded.startsWith("\\''", index),
                "Encoded value contains text outside a quoted segment",
            )
            append('\'')
            index += 3
        }
    }
    assertEquals(value, decoded)
}
