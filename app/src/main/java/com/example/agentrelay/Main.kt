/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Main : NavKey

@Serializable data object Settings : NavKey

@Serializable
data class SessionDetails(val sessionKey: String) : NavKey {
    init {
        require(sessionKey.matches(Regex("[0-9a-f]{64}"))) {
            "Session navigation key must be a SHA-256 identifier"
        }
    }
}
