/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.diagnostics

import android.os.Trace
import com.example.agentrelay.BuildConfig

enum class AndroidBuildMode { DEBUG, DIAGNOSTIC, PROFILEABLE, RELEASE }

data class DiagnosticRuntimeConfig(
    val mode: AndroidBuildMode,
    val profileable: Boolean,
    val runtimeProbes: Boolean,
    val coroutineDumps: Boolean,
) {
    val captureEnabledByDefault: Boolean = false

    fun allowsCoroutineDump(explicitOptIn: Boolean, nowMillis: Long, expiresAtMillis: Long): Boolean =
        coroutineDumps && explicitOptIn && nowMillis in 0 until expiresAtMillis

    companion object {
        fun fromBuildConfig(): DiagnosticRuntimeConfig {
            val mode = when (BuildConfig.AGENT_RELAY_BUILD_MODE) {
                "diagnostic" -> AndroidBuildMode.DIAGNOSTIC
                "profileable" -> AndroidBuildMode.PROFILEABLE
                "release" -> AndroidBuildMode.RELEASE
                else -> AndroidBuildMode.DEBUG
            }
            return when (mode) {
                AndroidBuildMode.DEBUG -> DiagnosticRuntimeConfig(mode, false, true, true)
                AndroidBuildMode.DIAGNOSTIC -> DiagnosticRuntimeConfig(mode, false, true, true)
                AndroidBuildMode.PROFILEABLE -> DiagnosticRuntimeConfig(mode, true, false, false)
                AndroidBuildMode.RELEASE -> DiagnosticRuntimeConfig(mode, false, false, false)
            }
        }
    }
}

object DiagnosticTrace {
    private val sectionPattern = Regex("[a-z][a-z0-9_.-]{0,63}")

    fun <T> section(config: DiagnosticRuntimeConfig, name: String, block: () -> T): T {
        require(name.matches(sectionPattern)) { "Trace section is not stable" }
        if (!config.runtimeProbes && !config.profileable) return block()
        Trace.beginSection(name)
        return try {
            block()
        } finally {
            Trace.endSection()
        }
    }
}
