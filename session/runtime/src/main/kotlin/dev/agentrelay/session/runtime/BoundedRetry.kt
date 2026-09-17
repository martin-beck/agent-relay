/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal suspend fun <T> retryBounded(
    maxAttempts: Int = 3,
    initialDelayMillis: Long = 1_000L,
    maxDelayMillis: Long = 4_000L,
    onRetry: suspend (attempt: Int, failure: Throwable, delayMillis: Long) -> Unit = { _, _, _ -> },
    operation: suspend () -> T,
): T {
    require(maxAttempts > 0)
    require(initialDelayMillis >= 0L)
    require(maxDelayMillis >= initialDelayMillis)
    var attempt = 1
    var delayMillis = initialDelayMillis
    while (true) {
        try {
            return operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (attempt >= maxAttempts) throw failure
            onRetry(attempt + 1, failure, delayMillis)
            delay(delayMillis)
            attempt += 1
            delayMillis = (delayMillis * 2L).coerceAtMost(maxDelayMillis)
        }
    }
}
